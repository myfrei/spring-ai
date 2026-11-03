package dev.myfrei.repopilot.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks real Boot/Spring AI HTTP wiring against a local Ollama protocol stub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(10)
class OllamaHttpIntegrationTest {

    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer OLLAMA = startOllamaStub();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url",
                () -> "http://localhost:" + OLLAMA.getAddress().getPort());
        registry.add("spring.ai.ollama.chat.model", () -> "test-model");
        registry.add("spring.http.clients.read-timeout", () -> "500ms");
    }

    @BeforeEach
    void resetStub() {
        MODE.set("ok");
        LAST_REQUEST.set(null);
        REQUESTS.set(0);
    }

    @AfterAll
    static void stopStub() {
        OLLAMA.stop(0);
        EXECUTOR.shutdownNow();
    }

    @Test
    void configuredClientCallsOllamaWithExpectedModelOptionsAndMessage() throws Exception {
        var response = ask("Что такое record?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(response.body()).read("$.answer", String.class))
                .isEqualTo("Record хранит данные.");
        var request = JsonPath.parse(LAST_REQUEST.get());
        assertThat(request.read("$.model", String.class)).isEqualTo("test-model");
        assertThat(request.read("$.messages[0].content", String.class)).isEqualTo("Что такое record?");
        assertThat(request.read("$.stream", Boolean.class)).isFalse();
        assertThat(request.read("$.options.temperature", Double.class)).isEqualTo(0.2);
        assertThat(request.read("$.options.num_predict", Integer.class)).isEqualTo(256);
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void unknownModelIsReportedAs503WithoutProviderDetails() throws Exception {
        MODE.set("missing-model");
        var response = ask("Вопрос");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("LLM unavailable").doesNotContain("private-detail");
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void configuredReadTimeoutBecomes503WithoutAnUnboundedWait() throws Exception {
        MODE.set("slow");
        var response = ask("Вопрос");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("LLM unavailable");
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void invalidInputNeverReachesOllama() throws Exception {
        var response = ask(" ");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(REQUESTS.get()).isZero();
    }

    private HttpResponse<String> ask(String message) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/chat"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"" + message + "\"}"))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer startOllamaStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/api/chat", exchange -> {
                REQUESTS.incrementAndGet();
                LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String mode = MODE.get();
                if (mode.equals("slow")) {
                    try {
                        Thread.sleep(1_500);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                String json = mode.equals("missing-model")
                        ? "{\"error\":\"model missing, private-detail\"}"
                        : """
                          {"model":"test-model","created_at":"2026-10-25T00:00:00Z",
                           "message":{"role":"assistant","content":"Record хранит данные."},
                           "done":true,"done_reason":"stop","total_duration":1000000,
                           "load_duration":1000,"prompt_eval_count":8,"prompt_eval_duration":1000,
                           "eval_count":5,"eval_duration":1000}
                          """;
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(mode.equals("missing-model") ? 404 : 200, body.length);
                    exchange.getResponseBody().write(body);
                }
                catch (IOException ignored) {
                    // A timeout may close the client connection before the delayed reply.
                }
                finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
