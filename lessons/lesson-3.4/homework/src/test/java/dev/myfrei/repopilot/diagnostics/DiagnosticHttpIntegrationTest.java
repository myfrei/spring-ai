package dev.myfrei.repopilot.diagnostics;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import com.jayway.jsonpath.JsonPath;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(15)
class DiagnosticHttpIntegrationTest {
    private static final Path DIRECTORY = directory();
    @LocalServerPort int port;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("repopilot.evaluation.enabled", () -> true); properties.add("repopilot.diagnostics.enabled", () -> true);
        properties.add("repopilot.evaluation.directory", () -> DIRECTORY.resolve("runs").toString());
        properties.add("repopilot.diagnostics.trace-directory", () -> DIRECTORY.resolve("traces").toString());
        properties.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
    }
    @AfterAll static void stop() throws IOException { try (var paths = Files.walk(DIRECTORY)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    @Test void labWorksWithoutOllamaAndPostgresAndReturnsInspectableTrace() throws Exception {
        var response = call("/lab", "POST"); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.rows[*].finding.category")).containsExactly("RETRIEVAL_MISS", "CONTEXT_DROPPED", "GENERATION_REVIEW");
        String traceId = JsonPath.read(response.body(), "$.rows[1].traceId"); var trace = call("/traces/" + traceId, "GET");
        assertThat(trace.statusCode()).isEqualTo(200); assertThat(JsonPath.<Boolean>read(trace.body(), "$.context.synthetic")).isTrue();
        assertThat(trace.body()).doesNotContain("TaskShelf запускается", "Fixture-ответ", "9999", "expectedAnswer");
    }
    @Test void laboratoryDoesNotPolluteMeasuredRunsAndMissingTraceIs404() throws Exception {
        call("/lab", "POST"); assertThat(call("/runs/latest", "GET").statusCode()).isEqualTo(404);
        assertThat(call("/traces/" + UUID.randomUUID(), "GET").statusCode()).isEqualTo(404);
    }
    private HttpResponse<String> call(String path, String method) throws Exception {
        try (var client = HttpClient.newHttpClient()) { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/diagnostics" + path)).timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()); }
    }
    private static Path directory() { try { return Files.createTempDirectory("diagnostic-http-"); } catch (IOException error) { throw new UncheckedIOException(error); } }
}
