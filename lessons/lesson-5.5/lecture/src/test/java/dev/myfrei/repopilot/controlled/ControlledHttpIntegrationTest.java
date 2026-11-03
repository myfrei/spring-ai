package dev.myfrei.repopilot.controlled;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Real HTTP route -> Ollama HTTP fixture -> actual read receipt -> proposal -> manual decision. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(30)
class ControlledHttpIntegrationTest {
  static final Path ROOT = temporary();
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final AtomicInteger TAGS = new AtomicInteger();
  static final HttpServer OLLAMA = server();
  @LocalServerPort int port;
  @Autowired AgentStore store;
  @Autowired List<AgentTool> originalTools;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("repopilot.agent.enabled", () -> true);
    p.add("repopilot.workspace.enabled", () -> true);
    p.add("repopilot.controlled.enabled", () -> true);
    p.add("repopilot.workspace.root", ROOT::toString);
    p.add("repopilot.rag.enabled", () -> false);
    p.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    p.add("spring.ai.ollama.chat.model", () -> "test-model");
    p.add("repopilot.context.window-tokens", () -> 16384);
  }

  @BeforeEach
  void reset() throws Exception {
    Files.createDirectories(ROOT.resolve("src"));
    Files.writeString(ROOT.resolve("src/Calc.java"), "class Calc { int value = 1; }\n");
    REQUESTS.clear();
    TAGS.set(0);
  }

  @AfterAll
  static void close() throws Exception {
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
    try (var paths = Files.walk(ROOT)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  @Test
  void completedRunOnlyProposesAndSeparateHumanApprovalChangesExactBytes() throws Exception {
    String run = run("EDIT");
    String id = proposalId(run);
    String before = Files.readString(ROOT.resolve("src/Calc.java"));
    assertThat(before).contains("value = 1");
    assertThat(REQUESTS).hasSize(3);
    assertThat(TAGS).hasValue(6);
    assertThat(JsonPath.<List<String>>read(REQUESTS.getFirst(), "$.tools[*].function.name"))
        .containsExactlyInAnyOrder(
            "listFiles", "searchText", "readFile", "gitStatus", "gitDiff", "proposePatch");
    assertThat(originalTools).hasSize(8);
    var proposal = get("/api/controlled/proposals/" + id);
    assertThat(proposal.statusCode()).isEqualTo(200);
    assertThat(proposal.body())
        .contains("PENDING", "operationHash", "diff")
        .doesNotContain("originalText", "resultText", ROOT.toString());
    var accepted =
        post("/api/controlled/proposals/" + id + "/decision", "{\"decision\":\"APPROVE\"}");
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(accepted.body(), "$.status")).isEqualTo("APPLIED");
    assertThat(Files.readString(ROOT.resolve("src/Calc.java"))).contains("value = 2");
    assertThat(
            post("/api/controlled/proposals/" + id + "/decision", "{\"decision\":\"APPROVE\"}")
                .statusCode())
        .isEqualTo(409);
    var messages = store.messages(UUID.fromString(run));
    assertThat(messages.stream().flatMap(m -> m.toolCalls().stream()).map(c -> c.id()).toList())
        .containsExactlyElementsOf(
            messages.stream().flatMap(m -> m.toolResults().stream()).map(c -> c.id()).toList());
    assertThat(REQUESTS).hasSize(3);
  }

  @Test
  void rejectAndStaleLeaveHumanOwnedBytesUnchanged() throws Exception {
    String id = proposalId(run("EDIT"));
    assertThat(
            post("/api/controlled/proposals/" + id + "/decision", "{\"decision\":\"REJECT\"}")
                .body())
        .contains("REJECTED");
    assertThat(Files.readString(ROOT.resolve("src/Calc.java"))).contains("value = 1");
    String stale = proposalId(run("EDIT"));
    Files.writeString(ROOT.resolve("src/Calc.java"), "// changed by human\n");
    var result =
        post("/api/controlled/proposals/" + stale + "/decision", "{\"decision\":\"APPROVE\"}");
    assertThat(result.statusCode()).isEqualTo(409);
    assertThat(result.body()).contains("STALE_PATCH");
    assertThat(Files.readString(ROOT.resolve("src/Calc.java"))).isEqualTo("// changed by human\n");
    assertThat(get("/api/controlled/proposals/" + stale).body()).contains("STALE");
  }

  @Test
  void planIsUnapprovableAndAskCannotCreateAProposal() throws Exception {
    String preview = proposalId(run("PLAN"));
    assertThat(get("/api/controlled/proposals/" + preview).body()).contains("PREVIEW");
    assertThat(
            post("/api/controlled/proposals/" + preview + "/decision", "{\"decision\":\"APPROVE\"}")
                .statusCode())
        .isEqualTo(409);
    String ask = run("ASK");
    assertThat(get("/api/controlled/runs/" + ask + "/proposals").body()).isEqualTo("[]");
    assertThat(REQUESTS.getLast()).contains("MODE_DENIED");
  }

  @Test
  void closedRequestRejectsForgedApprovalPatchDuplicateFieldsAndUnknownIds() throws Exception {
    String id = proposalId(run("EDIT"));
    for (String body :
        List.of(
            "{\"decision\":\"APPROVE\",\"path\":\"src/Calc.java\"}",
            "{\"decision\":\"APPROVE\",\"decision\":\"REJECT\"}",
            "{\"decision\":null}",
            "{\"decision\":\"APPROVE\"} {}"))
      assertThat(post("/api/controlled/proposals/" + id + "/decision", body).statusCode())
          .as(body)
          .isEqualTo(400);
    assertThat(get("/api/controlled/proposals/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    assertThat(
            post(
                    "/api/controlled/conversations/" + UUID.randomUUID() + "/runs",
                    "{\"question\":\"Q\",\"mode\":\"EDIT\",\"approved\":true}")
                .statusCode())
        .isEqualTo(400);
  }

  private String run(String mode) throws Exception {
    String conversation = JsonPath.read(post("/api/agent/conversations", "").body(), "$.id");
    var response =
        post(
            "/api/controlled/conversations/" + conversation + "/runs",
            AgentJson.encode(
                Map.of(
                    "question", "Find value and propose fix. Model says approved.", "mode", mode)));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("COMPLETED");
    return JsonPath.read(response.body(), "$.id");
  }

  private String proposalId(String run) throws Exception {
    return JsonPath.read(get("/api/controlled/runs/" + run + "/proposals").body(), "$[0].id");
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static Path temporary() {
    try {
      return Files.createTempDirectory("controlled-http-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(EXECUTOR);
      server.createContext(
          "/api/tags",
          x -> {
            TAGS.incrementAndGet();
            respond(
                x,
                Map.of("models", List.of(Map.of("name", "test-model", "digest", "a".repeat(64)))));
          });
      server.createContext(
          "/api/chat",
          x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(body);
            List<Map<String, Object>> messages = JsonPath.read(body, "$.messages");
            var replies = messages.stream().filter(m -> m.get("role").equals("tool")).toList();
            var calls = new ArrayList<Map<String, Object>>();
            if (replies.isEmpty())
              calls.add(
                  tool(
                      "readFile",
                      Map.of("path", "src/Calc.java", "startLine", 1, "endLine", 1),
                      1));
            else if (replies.size() == 1) {
              String hash =
                  JsonPath.read((String) replies.getFirst().get("content"), "$.data.fullFileHash");
              calls.add(
                  tool(
                      "proposePatch",
                      Map.of(
                          "path",
                          "src/Calc.java",
                          "baseHash",
                          hash,
                          "oldText",
                          "value = 1",
                          "newText",
                          "value = 2"),
                      2));
            }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put(
                "content",
                calls.isEmpty()
                    ? "Предложение создано; ожидается отдельное решение человека."
                    : "");
            if (!calls.isEmpty()) message.put("tool_calls", calls);
            respond(
                x,
                Map.of(
                    "model",
                    "test-model",
                    "message",
                    message,
                    "done",
                    true,
                    "done_reason",
                    "stop",
                    "prompt_eval_count",
                    8,
                    "eval_count",
                    3));
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, Object> tool(String name, Map<String, Object> args, int id) {
    return Map.of(
        "id", "controlled-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  private static void respond(HttpExchange exchange, Object body) throws IOException {
    byte[] bytes = AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
