package dev.myfrei.repopilot.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(15)
class StreamingHttpIntegrationTest {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicReference<String> REQUEST = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile CountDownLatch allowFinish = new CountDownLatch(1);
    private static volatile CountDownLatch disconnected = new CountDownLatch(1);
    private static final HttpServer OLLAMA = startStub();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:" + OLLAMA.getAddress().getPort());
        registry.add("spring.ai.ollama.chat.model", () -> "test-model");
        registry.add("repopilot.stream.idle-timeout", () -> "500ms");
        registry.add("repopilot.stream.max-duration", () -> "10s");
        registry.add("repopilot.stream.heartbeat", () -> "100ms");
    }

    @BeforeEach
    void reset() {
        MODE.set("ok"); REQUEST.set(null); CALLS.set(0);
        allowFinish = new CountDownLatch(1); disconnected = new CountDownLatch(1);
    }

    @AfterEach
    void release() { allowFinish.countDown(); }

    @AfterAll
    static void close() { OLLAMA.stop(0); EXECUTOR.shutdownNow(); }

    @Test
    void firstDeltaArrivesBeforeProviderFinishesAndUsesStreamingRequest() throws Exception {
        MODE.set("controlled");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request("Вопрос"), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String first = readThroughDelta(reader);
                assertThat(first).contains("event:started", "event:delta", "Первая");
                assertThat(allowFinish.getCount()).isEqualTo(1);
                var upstream = JsonPath.parse(REQUEST.get());
                assertThat(upstream.read("$.stream", Boolean.class)).isTrue();
                assertThat(upstream.read("$.model", String.class)).isEqualTo("test-model");
                assertThat(upstream.read("$.messages[0].role", String.class)).isEqualTo("system");
                allowFinish.countDown();
                String rest = reader.lines().reduce("", (a, b) -> a + b + "\n");
                assertThat(rest).contains("event:completed").doesNotContain("event:failed");
            }
        }
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void validationStillReturns400ProblemDetailBeforeStreamStarts() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request(" "), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
            assertThat(response.body()).doesNotContain("event:started");
        }
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void provider404AfterStartedIsFailedEventInsideHttp200() throws Exception {
        MODE.set("missing");
        String stream = readAll();
        assertThat(stream).contains("event:started", "event:failed", "MODEL_ERROR")
                .doesNotContain("event:completed", "private-detail");
    }

    @Test
    void cleanProviderEofWithoutDoneReasonDoesNotBecomeCompleted() throws Exception {
        MODE.set("incomplete");
        assertThat(readAll()).contains("Первая", "event:failed", "UPSTREAM_INCOMPLETE")
                .doesNotContain("event:completed");
    }

    @Test
    void idleModelSendsHeartbeatThenTimeoutAndNeverCompleted() throws Exception {
        MODE.set("stall");
        assertThat(readAll()).contains("keepalive", "event:failed", "TIMEOUT")
                .doesNotContain("event:completed");
    }

    @Test
    void lengthFinishIsReportedAsUnfinishedAnswer() throws Exception {
        MODE.set("length");
        assertThat(readAll()).contains("Первая", "OUTPUT_LIMIT", "event:failed")
                .doesNotContain("event:completed");
    }

    @Test
    void closingBrowserSideStreamReleasesUpstreamConnection() throws Exception {
        MODE.set("endless");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request("Вопрос"), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertThat(readThroughDelta(reader)).contains("event:delta");
            }
            assertThat(disconnected.await(3, TimeUnit.SECONDS)).as("upstream disconnect before 10s deadline").isTrue();
        }
    }

    private HttpRequest request(String message) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/chat/stream"))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/problem+json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"" + message + "\"}"))
                .build();
    }

    private String readAll() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request("Вопрос"), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    private static String readThroughDelta(BufferedReader reader) throws IOException {
        var result = new StringBuilder();
        boolean delta = false;
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line).append('\n');
            if (line.equals("event:delta")) delta = true;
            if (delta && line.isEmpty()) return result.toString();
        }
        throw new AssertionError("No delta frame before EOF: " + result);
    }

    private static HttpServer startStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/api/chat", exchange -> {
                CALLS.incrementAndGet();
                REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String mode = MODE.get();
                CountDownLatch finish = allowFinish;
                CountDownLatch closed = disconnected;
                try {
                    if (mode.equals("missing")) {
                        byte[] body = "{\"error\":\"private-detail\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(404, body.length);
                        exchange.getResponseBody().write(body);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                    exchange.sendResponseHeaders(200, 0);
                    write(exchange, "Первая", null);
                    if (mode.equals("controlled") || mode.equals("stall")) finish.await(4, TimeUnit.SECONDS);
                    if (mode.equals("incomplete") || mode.equals("stall")) return;
                    if (mode.equals("endless")) {
                        for (int i = 0; i < 250; i++) {
                            write(exchange, "x".repeat(1024), null);
                            Thread.sleep(20);
                        }
                    } else {
                        write(exchange, " часть.", null);
                        write(exchange, "", mode.equals("length") ? "length" : "stop");
                    }
                } catch (IOException ex) {
                    closed.countDown();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start(); return server;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    private static void write(HttpExchange exchange, String text, String reason) throws IOException {
        String terminal = reason == null ? "\"done\":false"
                : "\"done\":true,\"done_reason\":\"" + reason + "\",\"prompt_eval_count\":8,\"eval_count\":3";
        String json = "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + text + "\"}," + terminal + "}\n";
        exchange.getResponseBody().write(json.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
}
