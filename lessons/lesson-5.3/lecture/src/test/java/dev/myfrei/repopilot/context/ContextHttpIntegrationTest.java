package dev.myfrei.repopilot.context;

import dev.myfrei.repopilot.conversation.SqliteHistory;
import dev.myfrei.repopilot.conversation.Turn;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(15)
class ContextHttpIntegrationTest {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer OLLAMA = stub();
    @LocalServerPort int port;
    @Autowired SqliteHistory history;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:" + OLLAMA.getAddress().getPort());
        registry.add("repopilot.history.max-messages", () -> "3");
        registry.add("repopilot.stream.heartbeat", () -> "50ms");
    }
    @BeforeEach void reset() { MODE.set("ok"); REQUESTS.clear(); }
    @AfterAll static void stop() { OLLAMA.stop(0); EXECUTOR.shutdownNow(); }

    @Test void corpusManifestAndDocumentsHaveStableIdsAndPlainTextRoutes() throws Exception {
        var response = call("GET", "/api/corpus", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.version")).matches("[0-9a-f]{64}");
        assertThat(JsonPath.<List<String>>read(response.body(), "$.documents[*].id")).containsExactly("overview", "runbook", "runtime");
        var document = call("GET", "/api/corpus/documents/overview", null);
        assertThat(document.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(document.body()).contains("TaskShelf", "8091");
        assertThat(call("GET", "/api/corpus/documents/missing", null).statusCode()).isEqualTo(404);
    }

    @Test void previewDefaultsToChatDoesNotCreateTurnOrCallModelAndRejectsUnknownMode() throws Exception {
        String id = create();
        var result = call("POST", contextPath(id, "preview"), "{\"message\":\"Question\"}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(result.body(), "$.mode")).isEqualTo("CHAT");
        assertThat(JsonPath.<List<?>>read(result.body(), "$.sources")).isEmpty();
        assertThat(call("POST", contextPath(id, "preview"), "{\"message\":\"Q\",\"mode\":\"RAG\"}").statusCode()).isEqualTo(400);
        assertThat(history.turns(id)).isEmpty(); assertThat(REQUESTS).isEmpty();
    }

    @Test void fullPreviewReportsBudgetAndWholeCorpusVersion() throws Exception {
        String id = create();
        var preview = call("POST", contextPath(id, "preview"), body("Какой порт?", "FULL_CONTEXT"));
        assertThat(preview.statusCode()).isEqualTo(200);
        var report = JsonPath.parse(preview.body());
        assertThat(report.<List<String>>read("$.sources[*].id")).containsExactly("overview", "runbook", "runtime");
        assertThat(report.<String>read("$.corpusVersion")).isEqualTo(JsonPath.<String>read(call("GET", "/api/corpus", null).body(), "$.version"));
        assertThat(report.<Integer>read("$.inputBudget")).isEqualTo(3584);
        assertThat(report.<Integer>read("$.estimatedInputTokens")).isPositive().isLessThanOrEqualTo(3584);
        assertThat(report.<String>read("$.cacheEvidence")).isEqualTo("NOT_MEASURED");
        assertThat(history.turns(id)).isEmpty(); assertThat(REQUESTS).isEmpty();
    }

    @Test void fullStreamSendsContextBeforeTextAndActualPromptContainsAllDocumentsAndOptions() throws Exception {
        String id = create();
        var response = call("POST", contextPath(id, "stream"), body("Какой порт TaskShelf?", "FULL_CONTEXT"));
        assertThat(response.body()).contains("event:started", "event:context", "event:completed", "FULL_CONTEXT", "overview", "runbook", "runtime");
        assertThat(response.body().indexOf("event:context")).isLessThan(response.body().indexOf("event:delta"));
        assertThat(REQUESTS).hasSize(1);
        var request = JsonPath.parse(REQUESTS.getFirst());
        String user = request.read("$.messages[-1].content");
        assertThat(user).contains("TaskShelf", "8091", "Atlas", "SQLite", "TS-42", "sha256");
        assertThat(request.<Integer>read("$.options.num_ctx")).isEqualTo(4096);
        assertThat(request.<Integer>read("$.options.num_predict")).isEqualTo(256);
        assertThat(history.turns(id).getFirst().question()).isEqualTo("Какой порт TaskShelf?");
        assertThat(history.turns(id).getFirst().status()).isEqualTo("completed");
    }

    @Test void chatStreamHasNoCorpusAndDoesNotAugmentQuestion() throws Exception {
        String id = create();
        var response = call("POST", contextPath(id, "stream"), body("Какой порт TaskShelf?", "CHAT"));
        assertThat(response.body()).contains("event:context", "\"mode\":\"CHAT\"", "\"sources\":[]");
        assertThat(contents(REQUESTS.getFirst())).containsExactly("Какой порт TaskShelf?");
        assertThat(REQUESTS.getFirst()).doesNotContain("Справочные документы JSON", "8091", "Atlas");
    }

    @Test void overflowIs413InPreviewAndFailedInStreamWithoutProviderCall() throws Exception {
        String id = create(); String question = "я".repeat(4000);
        var preview = call("POST", contextPath(id, "preview"), body(question, "FULL_CONTEXT"));
        assertThat(preview.statusCode()).isEqualTo(413); assertThat(history.turns(id)).isEmpty();
        var response = call("POST", contextPath(id, "stream"), body(question, "FULL_CONTEXT"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:started", "event:failed", "CONTEXT_TOO_LARGE")
                .doesNotContain("event:completed", "event:context");
        assertThat(REQUESTS).isEmpty();
        assertThat(history.turns(id).getFirst().status()).isEqualTo("failed");
        assertThat(history.turns(id).getFirst().errorCode()).isEqualTo("CONTEXT_TOO_LARGE");
    }

    @Test void nextContextUsesOriginalQuestionAndDoesNotDuplicateDocumentPayloadInHistory() throws Exception {
        String id = create();
        call("POST", contextPath(id, "stream"), body("Q1", "FULL_CONTEXT"));
        call("POST", contextPath(id, "stream"), body("Q2", "FULL_CONTEXT"));
        var texts = contents(REQUESTS.getLast());
        assertThat(texts).hasSize(3);
        assertThat(texts.getFirst()).isEqualTo("Q1");
        assertThat(texts.get(1)).isEqualTo("Локальный ответ");
        assertThat(texts.getLast()).startsWith("Q2").contains("Справочные документы JSON");
        assertThat(history.turns(id)).extracting(Turn::question).containsExactly("Q1", "Q2");
    }

    private static String contextPath(String id, String action) { return "/api/conversations/" + id + "/context/" + action; }
    private static String body(String message, String mode) { return "{\"message\":\"" + message + "\",\"mode\":\"" + mode + "\"}"; }

    private String create() throws Exception { return JsonPath.read(call("POST", "/api/conversations", "{\"title\":\"Тест\"}").body(), "$.id"); }
    private static String path(String id) { return "/api/conversations/" + id + "/messages/stream"; }
    private String stream(String id, String message) throws Exception {
        var response = call("POST", path(id), "{\"message\":\"" + message + "\"}");
        assertThat(response.statusCode()).isEqualTo(200); return response.body();
    }
    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) { return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString()); }
    }
    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, application/problem+json, text/event-stream, text/plain")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static List<String> contents(String body) {
        List<String> roles = JsonPath.read(body, "$.messages[*].role");
        List<String> texts = JsonPath.read(body, "$.messages[*].content");
        var result = new ArrayList<String>();
        for (int i = 0; i < roles.size(); i++) if (!roles.get(i).equals("system")) result.add(texts.get(i));
        return result;
    }
    private static HttpServer stub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/api/chat", exchange -> {
                REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String mode = MODE.get();
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson"); exchange.sendResponseHeaders(200, 0);
                try {
                    String first = "{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"Локальный \"},\"done\":false}\n";
                    exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                    if (mode.equals("slow")) {
                        for (int i = 0; i < 300; i++) {
                            exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush(); Thread.sleep(20);
                        }
                    }
                    if (!mode.equals("broken")) {
                        String end = "{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"ответ\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":8,\"eval_count\":3}\n";
                        exchange.getResponseBody().write(end.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) { /* Client disconnected. */ }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}
