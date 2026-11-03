package dev.myfrei.repopilot.evaluation;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(20)
class EvaluationHttpIntegrationTest {
    private static final Path DIRECTORY = directory();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final AtomicReference<String> DIGEST = new AtomicReference<>("a".repeat(64));
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer OLLAMA = stub();
    @LocalServerPort int port;
    @Autowired RunStore store;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("repopilot.evaluation.enabled", () -> true);
        properties.add("repopilot.diagnostics.enabled", () -> true);
        properties.add("repopilot.diagnostics.trace-directory", () -> DIRECTORY.resolve("traces").toString());
        properties.add("repopilot.evaluation.directory", DIRECTORY::toString);
        properties.add("spring.ai.ollama.base-url", () -> "http://localhost:" + OLLAMA.getAddress().getPort());
        properties.add("spring.ai.ollama.chat.model", () -> "test-model");
    }
    @BeforeEach void reset() { DIGEST.set("a".repeat(64)); MODE.set("ok"); CALLS.clear(); }
    @AfterAll static void stop() throws IOException {
        OLLAMA.stop(0); EXECUTOR.shutdownNow();
        try (var paths = Files.walk(DIRECTORY)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    @Test void sameQuestionEmptyHistoryOptionsRealStreamingUsageAndExport() throws Exception {
        var run = run("{\"caseIds\":[\"dev-01\",\"dev-02\"],\"modes\":[\"CHAT\",\"FULL_CONTEXT\"],\"warmup\":true}");
        assertThat(run.state()).isEqualTo(State.COMPLETED); assertThat(run.results()).hasSize(4); assertThat(run.warmups()).hasSize(2); assertThat(CALLS).hasSize(6);
        assertThat(run.conditions().cacheEvidence()).isEqualTo("NOT_MEASURED"); assertThat(run.conditions().chat().digest()).isEqualTo("a".repeat(64));
        for (String call : CALLS) {
            assertThat(call).doesNotContain("expectedAnswer", "evidenceGroups");
            assertThat(JsonPath.<List<String>>read(call, "$.messages[*].role")).containsExactly("system", "user");
            assertThat(JsonPath.<Boolean>read(call, "$.stream")).isTrue(); assertThat(JsonPath.<Double>read(call, "$.options.temperature")).isEqualTo(.2);
            assertThat(JsonPath.<Integer>read(call, "$.options.num_ctx")).isEqualTo(4096); assertThat(JsonPath.<Integer>read(call, "$.options.num_predict")).isEqualTo(256);
        }
        for (int i = 0; i < run.results().size(); i++) {
            var row = run.results().get(i); String call = CALLS.get(i + 2);
            List<Map<String, String>> messages = JsonPath.read(call, "$.messages");
            var fields = new ArrayList<String>(); fields.add("prompt-v1");
            for (var message : messages) { fields.add(message.get("role")); fields.add(message.get("content")); }
            String hash = Hashes.identity(fields.toArray(String[]::new));
            assertThat(row.promptHash()).isEqualTo(hash); assertThat(row.providerInputTokens()).isEqualTo(123); assertThat(row.providerOutputTokens()).isEqualTo(7);
            assertThat(row.timing().endToEndTtftMs()).isGreaterThanOrEqualTo(row.timing().modelTtftMs());
            assertThat(row.sources()).hasSize(row.mode() == Mode.CHAT ? 0 : 3);
        }
        var judged = call("/runs/" + run.id() + "/judgments/0", "PUT", "{\"answer\":2,\"citations\":1,\"notes\":\"manual fixture\"}"); assertThat(judged.statusCode()).isEqualTo(200);
        var report = call("/runs/" + run.id() + "/report.md", "GET", null);
        assertThat(report.body()).contains("manual fixture", "Исходные результаты", "NOT_MEASURED");
        assertThat(callAbsolute("/api/conversations", "GET", null).body()).isEqualTo("[]");
    }
    @Test void outputLimitIsKeptAsFailureAndNotAQuickSuccessfulResponse() throws Exception {
        MODE.set("length"); var run = run("{\"caseIds\":[\"dev-01\"],\"modes\":[\"CHAT\"],\"warmup\":false}");
        assertThat(run.state()).isEqualTo(State.COMPLETED); assertThat(run.results().getFirst().code()).isEqualTo("OUTPUT_LIMIT");
        assertThat(ComparisonSummary.summarize(run).getFirst().totalMs().n()).isZero();
    }
    @Test void digestChangeInvalidatesCollectedAnswerAndStopsRemainingRequests() throws Exception {
        MODE.set("change"); var run = run("{\"caseIds\":[\"dev-01\",\"dev-02\"],\"modes\":[\"CHAT\"],\"warmup\":false}");
        assertThat(run.state()).isEqualTo(State.FAILED); assertThat(run.results()).hasSize(1); assertThat(run.results().getFirst().code()).isEqualTo("EXPERIMENT_CHANGED");
        assertThat(CALLS).hasSize(1);
    }
    @Test void asynchronousRunIsBusyAndCanBeCancelled() throws Exception {
        MODE.set("stall"); var started = call("/runs", "POST", "{\"caseIds\":[\"dev-01\",\"dev-02\"],\"modes\":[\"CHAT\"],\"warmup\":false}");
        assertThat(started.statusCode()).isEqualTo(202); UUID id = UUID.fromString(JsonPath.read(started.body(), "$.id"));
        await().atMost(Duration.ofSeconds(3)).until(() -> !CALLS.isEmpty());
        assertThat(call("/runs", "POST", "{}").statusCode()).isEqualTo(409);
        assertThat(call("/runs/" + id + "/cancel", "POST", "{}").statusCode()).isEqualTo(200);
        var run = terminal(id); assertThat(run.state()).isEqualTo(State.CANCELLED); assertThat(run.results()).hasSizeLessThan(2); assertThat(CALLS).hasSize(1);
    }
    @Test void heldoutCannotAccidentallyRunDevelopmentIdsAndBadOptionsAre400() throws Exception {
        assertThat(call("/runs", "POST", "{\"split\":\"HOLDOUT\",\"caseIds\":[\"dev-01\"]}").statusCode()).isEqualTo(400);
        assertThat(call("/runs", "POST", "{\"repeats\":9}").statusCode()).isEqualTo(400);
        assertThat(JsonPath.<List<?>>read(call("/dataset?split=HOLDOUT", "GET", null).body(), "$.cases")).hasSize(10);
        assertThat(CALLS).isEmpty();
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void traceCorrelatesRealStreamingStagesWithoutRawPayload(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        MODE.set("marker"); var run = run("{\"caseIds\":[\"dev-01\"],\"modes\":[\"FULL_CONTEXT\"],\"warmup\":false}");
        var row = run.results().getFirst(); assertThat(row.traceId()).matches("[0-9a-f-]{36}");
        var response = callAbsolute("/api/diagnostics/traces/" + row.traceId(), "GET", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.context.runId")).isEqualTo(run.id().toString());
        assertThat(JsonPath.<List<String>>read(response.body(), "$.events[*].stage")).contains("GUARD", "ASSEMBLY", "GENERATION", "FIRST_TEXT", "VERIFY", "FINISH");
        assertThat(response.body()).doesNotContain("RAW_ANSWER_MARKER_DO_NOT_LOG", "Какой порт использует TaskShelf?", "TaskShelf хранит задачи");
        assertThat(output.getAll()).contains("trace_event").doesNotContain("RAW_ANSWER_MARKER_DO_NOT_LOG", "Какой порт использует TaskShelf?", "TaskShelf хранит задачи");
    }
    private Experiment run(String request) throws Exception {
        var response = call("/runs", "POST", request); assertThat(response.statusCode()).isEqualTo(202);
        return terminal(UUID.fromString(JsonPath.read(response.body(), "$.id")));
    }
    private Experiment terminal(UUID id) { await().atMost(Duration.ofSeconds(6)).until(() -> store.load(id).state() != State.RUNNING); return store.load(id); }
    private HttpResponse<String> call(String path, String method, String body) throws Exception { return callAbsolute("/api/evaluation" + path, method, body); }
    private HttpResponse<String> callAbsolute(String path, String method, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private static HttpServer stub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(EXECUTOR);
            server.createContext("/api/tags", exchange -> respond(exchange, "application/json", "{\"models\":[{\"name\":\"test-model\",\"digest\":\"" + DIGEST.get() + "\"}]}"));
            server.createContext("/api/chat", exchange -> {
                CALLS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); String mode = MODE.get();
                if (mode.equals("change")) DIGEST.set("b".repeat(64));
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson"); exchange.sendResponseHeaders(200, 0);
                try {
                    write(exchange, mode.equals("marker") ? "RAW_ANSWER_MARKER_DO_NOT_LOG" : "Ответ fixture.", null);
                    if (mode.equals("stall")) Thread.sleep(8000);
                    write(exchange, "", mode.equals("length") ? "length" : "stop");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { } finally { exchange.close(); }
            }); server.start(); return server;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static void write(HttpExchange exchange, String text, String reason) throws IOException {
        var json = new LinkedHashMap<String, Object>(); json.put("model", "test-model"); json.put("message", Map.of("role", "assistant", "content", text));
        json.put("done", reason != null); if (reason != null) { json.put("done_reason", reason); json.put("prompt_eval_count", 123); json.put("eval_count", 7); }
        exchange.getResponseBody().write((JsonMapper.builder().build().writeValueAsString(json) + "\n").getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
    }
    private static void respond(HttpExchange exchange, String type, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static Path directory() { try { return Files.createTempDirectory("evaluation-http-"); } catch (IOException error) { throw new UncheckedIOException(error); } }
}
