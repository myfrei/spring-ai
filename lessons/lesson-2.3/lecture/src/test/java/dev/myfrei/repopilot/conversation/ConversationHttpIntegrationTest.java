package dev.myfrei.repopilot.conversation;

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
class ConversationHttpIntegrationTest {
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

    @Test void createsListsAndRetrievesConversationWithLocation() throws Exception {
        var created = call("POST", "/api/conversations", "{\"title\":\"Java\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.id");
        assertThat(created.headers().firstValue("Location").orElse("")).isEqualTo("/api/conversations/" + id);
        assertThat(call("GET", "/api/conversations", null).body()).contains(id, "Java");
        assertThat(call("GET", "/api/conversations/" + id, null).body()).contains("\"turns\":[]");
    }

    @Test void validatesTitleMessageUuidAndMissingConversationBeforeSse() throws Exception {
        assertThat(call("POST", "/api/conversations", "{\"title\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/conversations/not-a-uuid", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/conversations/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        var unknown = call("POST", path(UUID.randomUUID().toString()), "{\"message\":\"Вопрос\"}");
        assertThat(unknown.statusCode()).isEqualTo(404); assertThat(unknown.body()).doesNotContain("event:started");
        var invalid = call("POST", path(create()), "{\"message\":\" \"}");
        assertThat(invalid.statusCode()).isEqualTo(400); assertThat(REQUESTS).isEmpty();
    }

    @Test void memoryAdvisorActuallySendsOwnHistoryAndNeverOtherConversation() throws Exception {
        String a = create(); String b = create();
        stream(a, "Меня зовут Анна"); stream(a, "Как меня зовут?");
        assertThat(contents(REQUESTS.get(1))).containsExactly("Меня зовут Анна", "Локальный ответ", "Как меня зовут?");
        stream(b, "Чужой диалог");
        assertThat(contents(REQUESTS.get(2))).containsExactly("Чужой диалог");
        assertThat(history.turns(a)).hasSize(2); assertThat(history.turns(b)).hasSize(1);
    }

    @Test void messageWindowEvictsOldPairWithoutDeletingHistory() throws Exception {
        String id = create(); stream(id, "Q1"); stream(id, "Q2"); stream(id, "Q3");
        assertThat(contents(REQUESTS.get(2))).containsExactly("Q2", "Локальный ответ", "Q3");
        assertThat(history.turns(id)).extracting(Turn::question).containsExactly("Q1", "Q2", "Q3");
    }

    @Test void failedTurnKeepsPartialButDoesNotPolluteNextPrompt() throws Exception {
        String id = create(); MODE.set("broken");
        assertThat(stream(id, "Неудачный")).contains("event:failed", "UPSTREAM_INCOMPLETE").doesNotContain("event:completed");
        assertThat(history.turns(id).getFirst().answer()).isEqualTo("Локальный ");
        assertThat(history.turns(id).getFirst().status()).isEqualTo("failed");
        MODE.set("ok"); stream(id, "Новый");
        assertThat(contents(REQUESTS.get(1))).containsExactly("Новый");
    }

    @Test void disconnectPersistsCancellationThenAcceptsNextRequest() throws Exception {
        String id = create(); MODE.set("slow");
        try (var client = HttpClient.newHttpClient()) {
            var result = client.send(request("POST", path(id), "{\"message\":\"Длинный\"}"), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(result.body(), StandardCharsets.UTF_8))) {
                boolean delta = false;
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.equals("event:delta")) delta = true;
                    if (delta && line.isEmpty()) break;
                }
                assertThat(delta).isTrue();
            }
        }
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(history.turns(id).getFirst().status()).isEqualTo("cancelled"));
        assertThat(history.turns(id).getFirst().answer()).startsWith("Локальный ");
        MODE.set("ok"); assertThat(stream(id, "Следующий")).contains("event:completed");
    }

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
                .header("Content-Type", "application/json").header("Accept", "application/json, application/problem+json, text/event-stream")
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
