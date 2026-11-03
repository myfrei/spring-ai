package dev.myfrei.repopilot.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(15)
class IngestionHttpIntegrationTest {
    private static final String MODEL = "embeddinggemma:300m";
    private static final AtomicReference<String> DIGEST = new AtomicReference<>("a".repeat(64));
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicInteger TAG_CALLS = new AtomicInteger();
    private static final List<String> EMBEDS = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Path DIRECTORY = directory();
    private static final Path INDEX = DIRECTORY.resolve("index.json");
    private static final HttpServer OLLAMA = stub();
    private static final String OPTIONS = "{\"strategy\":\"MARKDOWN_SECTIONS\",\"maxCodePoints\":300,\"overlapCodePoints\":40}";
    @LocalServerPort int port;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:" + OLLAMA.getAddress().getPort());
        registry.add("spring.ai.ollama.embedding.model", () -> MODEL);
        registry.add("repopilot.ingestion.dimensions", () -> 3);
        registry.add("repopilot.ingestion.batch-size", () -> 3);
        registry.add("repopilot.ingestion.index-file", INDEX::toString);
    }
    @BeforeEach void reset() throws IOException {
        MODE.set("ok"); DIGEST.set("a".repeat(64)); TAG_CALLS.set(0); EMBEDS.clear(); Files.deleteIfExists(INDEX);
    }
    @AfterAll static void stop() throws IOException {
        OLLAMA.stop(0); EXECUTOR.shutdownNow();
        try (var files = Files.list(DIRECTORY)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(DIRECTORY);
    }

    @Test void previewHasProvenanceWithoutCallingEmbeddingRuntime() throws Exception {
        var response = call("/preview", OPTIONS);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JsonPath.parse(response.body());
        assertThat(json.<List<String>>read("$.chunks[*].documentId")).contains("overview", "runbook", "runtime");
        assertThat(json.<List<String>>read("$.chunks[*].id")).allMatch(id -> id.matches("[0-9a-f]{64}"));
        assertThat(json.<List<Integer>>read("$.chunks[*].startLine")).allMatch(line -> line >= 1);
        assertThat(EMBEDS).isEmpty(); assertThat(TAG_CALLS.get()).isZero(); assertThat(INDEX).doesNotExist();
    }

    @Test void realSpringEmbeddingModelSendsBatchAndNoTruncationThenPublishesSnapshot() throws Exception {
        var response = call("/index", OPTIONS); assertThat(response.statusCode()).isEqualTo(200);
        var result = JsonPath.parse(response.body());
        assertThat(result.<Boolean>read("$.reused")).isFalse();
        assertThat(result.<String>read("$.embedding.digest")).isEqualTo(DIGEST.get());
        assertThat(result.<Integer>read("$.embedding.dimensions")).isEqualTo(3);
        assertThat(EMBEDS).isNotEmpty();
        int embeddedCount = 0;
        for (String body : EMBEDS) {
            var request = JsonPath.parse(body);
            assertThat(request.<String>read("$.model")).isEqualTo(MODEL);
            assertThat(request.<Boolean>read("$.truncate")).isFalse();
            assertThat(request.<Integer>read("$.dimensions")).isEqualTo(3);
            assertThat(request.<Integer>read("$.options.num_ctx")).isEqualTo(2048);
            List<String> texts = request.read("$.input");
            assertThat(texts).hasSizeBetween(1, 3).allMatch(text -> text.startsWith("title: ") && text.contains(" | text: "));
            embeddedCount += texts.size();
        }
        assertThat(embeddedCount).isEqualTo(result.<Integer>read("$.chunkCount"));
        assertThat(new IndexArtifactStore(INDEX.toString()).load().orElseThrow().chunks()).hasSize(embeddedCount);
        assertThat(TAG_CALLS.get()).isEqualTo(2);
    }

    @Test void repeatedIndexSkipsEmbeddingsAndDigestUpdateTriggersRebuild() throws Exception {
        var first = call("/index", OPTIONS); int calls = EMBEDS.size();
        var second = call("/index", OPTIONS);
        assertThat(JsonPath.<Boolean>read(second.body(), "$.reused")).isTrue(); assertThat(EMBEDS).hasSize(calls);
        DIGEST.set("b".repeat(64));
        var third = call("/index", OPTIONS);
        assertThat(JsonPath.<Boolean>read(third.body(), "$.reused")).isFalse();
        assertThat(JsonPath.<String>read(third.body(), "$.fingerprint")).isNotEqualTo(JsonPath.<String>read(first.body(), "$.fingerprint"));
        assertThat(EMBEDS.size()).isGreaterThan(calls);
    }

    @Test void invalidDimensionsCannotReplacePreviousArtifact() throws Exception {
        call("/index", OPTIONS); byte[] previous = Files.readAllBytes(INDEX);
        DIGEST.set("b".repeat(64)); MODE.set("wrong-dimensions");
        var failed = call("/index", OPTIONS);
        assertThat(failed.statusCode()).isEqualTo(502); assertThat(failed.body()).contains("INVALID_VECTOR");
        assertThat(Files.readAllBytes(INDEX)).isEqualTo(previous);
    }

    @Test void responseForDifferentModelCannotReplacePreviousArtifact() throws Exception {
        call("/index", OPTIONS); byte[] previous = Files.readAllBytes(INDEX);
        DIGEST.set("b".repeat(64)); MODE.set("wrong-model");
        var failed = call("/index", OPTIONS);
        assertThat(failed.statusCode()).isEqualTo(409); assertThat(failed.body()).contains("MODEL_CHANGED");
        assertThat(Files.readAllBytes(INDEX)).isEqualTo(previous);
    }

    @Test void offlineStatusReadsSavedIndexButMissingRuntimeModelRejectsRebuild() throws Exception {
        call("/index", OPTIONS); int tagCalls = TAG_CALLS.get(); MODE.set("missing");
        var status = call("", null);
        assertThat(status.statusCode()).isEqualTo(200); assertThat(status.body()).contains("RUNTIME_NOT_CHECKED");
        assertThat(TAG_CALLS.get()).isEqualTo(tagCalls);
        var failed = call("/index", OPTIONS);
        assertThat(failed.statusCode()).isEqualTo(503); assertThat(failed.body()).contains("MODEL_NOT_FOUND");
        assertThat(INDEX).exists();
    }

    @Test void invalidChunkingParametersAre400BeforeRuntimeCalls() throws Exception {
        for (String body : List.of("{}", "{\"strategy\":\"MAGIC\",\"maxCodePoints\":300,\"overlapCodePoints\":40}",
                "{\"strategy\":\"FIXED_WINDOW\",\"maxCodePoints\":64,\"overlapCodePoints\":64}")) {
            assertThat(call("/preview", body).statusCode()).isEqualTo(400);
        }
        assertThat(TAG_CALLS.get()).isZero(); assertThat(EMBEDS).isEmpty();
    }

    private HttpResponse<String> call(String path, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ingestion" + path))
                    .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
                    .method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
    private static HttpServer stub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(EXECUTOR);
            server.createContext("/api/tags", exchange -> {
                TAG_CALLS.incrementAndGet();
                respond(exchange, MODE.get().equals("missing") ? "{\"models\":[]}"
                        : "{\"models\":[{\"name\":\"" + MODEL + "\",\"model\":\"" + MODEL + "\",\"digest\":\"" + DIGEST.get() + "\",\"size\":123}]}");
            });
            server.createContext("/api/embed", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); EMBEDS.add(request);
                List<String> texts = JsonPath.read(request, "$.input");
                String vector = MODE.get().equals("wrong-dimensions") ? "[1,0]" : "[1,0,0]";
                String model = MODE.get().equals("wrong-model") ? "other-model:1" : MODEL;
                respond(exchange, "{\"model\":\"" + model + "\",\"embeddings\":[" + String.join(",", texts.stream().map(ignored -> vector).toList()) + "],\"prompt_eval_count\":10}");
            });
            server.start(); return server;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static Path directory() { try { return Files.createTempDirectory("ingestion-http-"); } catch (IOException ex) { throw new UncheckedIOException(ex); } }
}
