package dev.myfrei.repopilot.rag;

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
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.ingestion.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Timeout(30)
class PgVectorRagIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"));
    private static final Path DIRECTORY = directory();
    private static final Path INDEX = DIRECTORY.resolve("index.json");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final AtomicReference<String> DIGEST = new AtomicReference<>("a".repeat(64));
    private static final AtomicReference<String> CHAT_REQUEST = new AtomicReference<>();
    private static final List<String> EMBEDS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean CHANGE_ON_QUERY = new AtomicBoolean();
    private static final AtomicInteger CHAT_CALLS = new AtomicInteger();
    private static final HttpServer OLLAMA = stub();
    private static final String OPTIONS = "{\"strategy\":\"MARKDOWN_SECTIONS\",\"maxCodePoints\":300,\"overlapCodePoints\":40}";
    @LocalServerPort int port;
    @Autowired RagDatabase database;
    @Autowired RagIndexPublisher publisher;
    @Autowired RagSearcher searcher;
    @Autowired RagPlanner planner;
    @Autowired CorpusCatalog corpus;
    @Autowired IndexArtifactStore artifacts;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("repopilot.rag.enabled", () -> true);
        properties.add("repopilot.rag.jdbc-url", POSTGRES::getJdbcUrl);
        properties.add("repopilot.rag.username", POSTGRES::getUsername);
        properties.add("repopilot.rag.password", POSTGRES::getPassword);
        properties.add("spring.ai.ollama.base-url", () -> "http://localhost:" + OLLAMA.getAddress().getPort());
        properties.add("repopilot.ingestion.dimensions", () -> 3);
        properties.add("repopilot.ingestion.index-file", INDEX::toString);
    }
    @BeforeEach void reset() throws IOException {
        database.ensureSchema();
        database.jdbc().execute("DROP TRIGGER IF EXISTS reject_rag_insert ON rag_vectors");
        database.jdbc().execute("TRUNCATE rag_vectors,rag_active_indexes");
        Files.deleteIfExists(INDEX); CHANGE_ON_QUERY.set(false); DIGEST.set("a".repeat(64)); EMBEDS.clear(); CHAT_CALLS.set(0); CHAT_REQUEST.set(null);
    }
    @AfterAll static void stop() throws IOException {
        OLLAMA.stop(0); EXECUTOR.shutdownNow();
        try (var paths = Files.list(DIRECTORY)) { for (var path : paths.toList()) Files.deleteIfExists(path); }
        Files.deleteIfExists(DIRECTORY);
    }
    @Test void publishesPreparedVectorsIdempotentlyWithoutSecondEmbeddingRequest() throws Exception {
        prepare(); int calls = EMBEDS.size();
        var first = call("/api/rag/publish", "{}"); assertThat(first.statusCode()).isEqualTo(200);
        var second = call("/api/rag/publish", "{}"); assertThat(second.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(second.body(), "$.reused")).isTrue();
        assertThat(EMBEDS).hasSize(calls);
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM rag_vectors", Integer.class))
                .isEqualTo(artifacts.load().orElseThrow().chunks().size());
        assertThat(database.jdbc().queryForObject("SELECT vector_dims(embedding) FROM rag_vectors LIMIT 1", Integer.class)).isEqualTo(3);
    }
    @Test void cosineThresholdTopKAndWorkspaceFiltersAreAppliedInActualSql() throws Exception {
        published();
        database.jdbc().execute("INSERT INTO rag_vectors SELECT 'foreign-'||id, content, jsonb_set(metadata::jsonb,'{workspaceId}','\"other\"')::json, embedding FROM rag_vectors");
        var result = searcher.search(new RagRequest("Порт запуска?", 2, .8));
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits()).allSatisfy(hit -> { assertThat(hit.source().score()).isCloseTo(1.0, within(.00001)); assertThat(hit.source().path()).isEqualTo("RUNBOOK.md"); });
        var none = searcher.search(new RagRequest("absent", 4, .35)); assertThat(none.hits()).isEmpty();
        String query = EMBEDS.getLast();
        assertThat(JsonPath.<List<String>>read(query, "$.input")).containsExactly("task: search result | query: absent");
        assertThat(JsonPath.<Boolean>read(query, "$.truncate")).isFalse();
        assertThat(JsonPath.<Integer>read(query, "$.dimensions")).isEqualTo(3);
        assertThat(CHAT_CALLS.get()).isZero();
    }
    @Test void sourceLinksReturnExactChunkAndExpiredSnapshotIs404() throws Exception {
        published(); var found = searcher.search(new RagRequest("Порт?", 1, .8)); var hit = found.hits().getFirst();
        String path = "/api/rag/chunks/" + found.manifest().fingerprint() + "/" + hit.source().chunkId();
        var source = call(path, null); assertThat(source.statusCode()).isEqualTo(200); assertThat(source.body()).endsWith(hit.text());
        prepare("{\"strategy\":\"FIXED_WINDOW\",\"maxCodePoints\":400,\"overlapCodePoints\":30}"); publisher.publish();
        assertThat(call(path, null).statusCode()).isEqualTo(404);
    }
    @Test void failureAfterDeleteRollsBackRowsAndActiveManifest() throws Exception {
        published(); var old = publisher.manifest().orElseThrow();
        prepare("{\"strategy\":\"FIXED_WINDOW\",\"maxCodePoints\":400,\"overlapCodePoints\":30}");
        database.jdbc().execute("CREATE OR REPLACE FUNCTION reject_rag() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only'; END $$");
        database.jdbc().execute("CREATE TRIGGER reject_rag_insert BEFORE INSERT ON rag_vectors FOR EACH ROW EXECUTE FUNCTION reject_rag()");
        assertThatThrownBy(publisher::publish).isInstanceOf(RagProblem.class);
        assertThat(publisher.manifest()).contains(old);
        assertThat(database.jdbc().queryForObject("SELECT count(*) FROM rag_vectors", Integer.class)).isEqualTo(old.chunkCount());
    }
    @Test void rejectsProfileChangeBeforeQueryEmbeddingAndDimensionMismatchWithoutDroppingData() throws Exception {
        published(); EMBEDS.clear(); DIGEST.set("b".repeat(64));
        assertThatThrownBy(() -> searcher.search(new RagRequest("Q", 1, .5))).isInstanceOf(RagProblem.class)
                .extracting("code").isEqualTo("INDEX_PROFILE_MISMATCH"); assertThat(EMBEDS).isEmpty();
        var incompatible = new RagDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 4);
        assertThatThrownBy(incompatible::ensureSchema).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("INDEX_DIMENSIONS");
        assertThat(publisher.manifest()).isPresent();
    }
    @Test void emptyRetrievalCompletesWithoutCallingChatModel() throws Exception {
        published(); String id = conversation();
        var response = call("/api/conversations/" + id + "/rag/stream", "{\"message\":\"absent\"}");
        assertThat(response.body()).contains("event:rag", "NO_EVIDENCE", "event:completed").doesNotContain("event:failed");
        assertThat(CHAT_CALLS.get()).isZero();
        assertThat(call("/api/conversations/" + id, null).body()).contains("completed", "подходящие фрагменты");
    }
    @Test void actualAdvisorPromptEqualsBudgetProjectionAndEmbedsQueryOnlyOnce() throws Exception {
        published(); String question = "Порт запуска? JSON {literal}";
        var expected = planner.plan(question, List.of(), searcher.search(new RagRequest(question, null, null)));
        EMBEDS.clear();
        var response = call("/api/conversations/" + conversation() + "/rag/stream", "{\"message\":\"" + question + "\"}");
        assertThat(response.body()).contains("event:rag", "event:delta", "event:completed").doesNotContain("event:failed");
        assertThat(CHAT_CALLS.get()).isEqualTo(1); assertThat(EMBEDS).hasSize(1);
        List<String> contents = JsonPath.read(CHAT_REQUEST.get(), "$.messages[*].content");
        assertThat(contents).containsExactlyElementsOf(planner.projected(expected).stream().map(message -> message.getText()).toList());
        assertThat(contents.getLast()).contains("[S1]", "chunkId=", "textHash=", "JSON {literal}");
    }
    @Test void badRetrievalOptionsAre400AndUnpublishedIndexHasPublicError() throws Exception {
        assertThat(call("/api/rag/search", "{\"message\":\"Q\",\"topK\":99}").statusCode()).isEqualTo(400);
        var response = call("/api/rag/search", "{\"message\":\"Q\"}");
        assertThat(response.statusCode()).isEqualTo(409); assertThat(response.body()).contains("INDEX_NOT_READY");
    }
    @Test void everyServerOwnedFilterExcludesForeignMetadataBeforeTopK() throws Exception {
        published();
        for (String field : List.of("workspaceId", "corpusVersion", "fingerprint", "embeddingDigest")) {
            database.jdbc().update("INSERT INTO rag_vectors SELECT ?||id,content,jsonb_set(jsonb_set(metadata::jsonb,ARRAY[?],to_jsonb(?::text)),'{documentId}','\"foreign-document\"')::json,embedding FROM rag_vectors WHERE metadata->>'workspaceId'='taskshelf' AND metadata->>'documentId'='runbook'", field, field, "foreign");
        }
        int expected = (int) artifacts.load().orElseThrow().chunks().stream().filter(entry -> entry.chunk().documentId().equals("runbook")).count();
        assertThat(expected).isLessThan(8);
        var found = searcher.search(new RagRequest("Порт?", 8, .8));
        assertThat(found.hits()).hasSize(expected);
        assertThat(found.hits()).extracting(hit -> hit.source().chunkId()).doesNotHaveDuplicates();
    }
    @Test void staleCorpusAndChangingRuntimeCannotReachChatModel() throws Exception {
        published(); CHANGE_ON_QUERY.set(true);
        assertThatThrownBy(() -> searcher.search(new RagRequest("Порт?", 1, .5))).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("MODEL_CHANGED");
        CHANGE_ON_QUERY.set(false); DIGEST.set("a".repeat(64)); EMBEDS.clear();
        database.jdbc().update("UPDATE rag_active_indexes SET manifest=jsonb_set(manifest,'{corpusVersion}','\"stale\"')");
        assertThatThrownBy(() -> searcher.search(new RagRequest("Q", 1, .5))).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("STALE_INDEX");
        assertThat(EMBEDS).isEmpty(); assertThat(CHAT_CALLS.get()).isZero();
    }
    @Test void sourceHashCorruptionIsAnExplicitError() throws Exception {
        published();
        database.jdbc().execute("UPDATE rag_vectors SET content='tampered' WHERE metadata->>'documentId'='runbook'");
        assertThatThrownBy(() -> searcher.search(new RagRequest("Порт?", 1, .8))).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("INDEX_CORRUPT");
    }
    private void prepare() throws Exception { prepare(OPTIONS); }
    private void prepare(String options) throws Exception {
        var response = call("/api/ingestion/index", options); assertThat(response.statusCode()).isEqualTo(200);
    }
    private void published() throws Exception { prepare(); publisher.publish(); }
    private String conversation() throws Exception { return JsonPath.read(call("/api/conversations", "{\"title\":\"RAG test\"}").body(), "$.id"); }
    private HttpResponse<String> call(String path, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
    private static HttpServer stub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(EXECUTOR);
            server.createContext("/api/tags", exchange -> respond(exchange, "application/json", "{\"models\":[{\"name\":\"embeddinggemma:300m\",\"digest\":\"" + DIGEST.get() + "\"}]}"));
            server.createContext("/api/embed", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); EMBEDS.add(body);
                List<String> texts = JsonPath.read(body, "$.input");
                if (CHANGE_ON_QUERY.get() && texts.getFirst().startsWith("task:")) DIGEST.set("b".repeat(64));
                var vectors = texts.stream().map(text -> text.startsWith("task:") ? (text.endsWith("absent") ? List.of(-1, 0, 0) : List.of(1, 0, 0))
                        : (text.contains("title: Запуск и диагностика") ? List.of(1, 0, 0) : List.of(0, 1, 0))).toList();
                respond(exchange, "application/json", JsonMapper.builder().build().writeValueAsString(Map.of("model", "embeddinggemma:300m", "embeddings", vectors)));
            });
            server.createContext("/api/chat", exchange -> {
                CHAT_CALLS.incrementAndGet(); CHAT_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, "application/x-ndjson", "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"Порт 8091 [S1].\"},\"done\":false}\n"
                        + "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":8,\"eval_count\":3}\n");
            });
            server.start(); return server;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static void respond(HttpExchange exchange, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static Path directory() { try { return Files.createTempDirectory("pgvector-rag-it-"); } catch (IOException error) { throw new UncheckedIOException(error); } }
}
