package dev.myfrei.repopilot.compaction;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.plan.*;
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

/** Real Boot -> Ollama HTTP fixture -> real readFile/plan tools -> opt-in bounded projection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(35)
class CompactionHttpIntegrationTest {
  static final Path ROOT = temporary();
  static final String CODE = "src/main/java/demo/Sample.java",
      TEST = "src/test/java/demo/SampleTest.java";
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final AtomicInteger TAGS = new AtomicInteger();
  static final AtomicReference<String> MODE = new AtomicReference<>("COMPLETE");
  static final HttpServer OLLAMA = server();
  @LocalServerPort int port;
  @Autowired AgentStore store;
  @Autowired ContextRuntime runtime;
  @Autowired PlanRuntime plans;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry props) {
    for (String flag : List.of("agent", "workspace", "controlled", "skills", "plans", "compaction"))
      props.add("repopilot." + flag + ".enabled", () -> true);
    props.add("repopilot.workspace.root", () -> ROOT.toString());
    props.add("repopilot.skills.root", () -> ROOT.resolve(".agents/skills").toString());
    props.add("repopilot.rag.enabled", () -> false);
    props.add("repopilot.context.window-tokens", () -> 24000);
    props.add(
        "spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    props.add("spring.ai.ollama.chat.model", () -> "test-model");
  }

  @BeforeEach
  void reset() throws Exception {
    for (String path : List.of(CODE, TEST)) {
      Files.createDirectories(ROOT.resolve(path).getParent());
      Files.writeString(
          ROOT.resolve(path),
          "class "
              + (path.equals(CODE) ? "Sample" : "SampleTest")
              + " { } // "
              + (path.equals(CODE) ? "CODE_PAYLOAD" : "TEST_PAYLOAD")
              + "x".repeat(2800)
              + "\n");
    }
    MODE.set("COMPLETE");
    REQUESTS.clear();
    TAGS.set(0);
  }

  @AfterAll
  static void close() throws Exception {
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
    try (var paths = Files.walk(ROOT)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  @Test
  void actualReadToolsCompactBeforeLaterModelCallButRawJournalAndServerPlanStayComplete()
      throws Exception {
    UUID conversation = store.createConversation().id();
    var response =
        run(conversation, 5500, "CURRENT_USER_CONSTRAINT: только исследование, без записи 😀");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.run.status"))
        .as(response.body())
        .isEqualTo("COMPLETED");
    UUID id = UUID.fromString(JsonPath.read(response.body(), "$.run.id"));
    var report = runtime.report(id);
    assertThat(REQUESTS).hasSize(4);
    assertThat(TAGS).hasValue(8);
    assertThat(report.frames())
        .as(report.toString())
        .anyMatch(frame -> frame.compactedExchanges() > 0);
    assertThat(report.frames())
        .allMatch(frame -> frame.admitted() && frame.projectedEstimatedTokens() <= 5500);
    assertThat(REQUESTS.getLast())
        .contains(
            "COMPACTED_TOOL_DATA",
            "payloadOmitted",
            "CURRENT_USER_CONSTRAINT",
            "только исследование",
            "mode=ASK",
            "CURRENT_PLAN_V1",
            "COMPLETED");
    assertThat(REQUESTS.getLast()).doesNotContain("CODE_PAYLOAD");
    assertThat(AgentJson.encode(store.messages(id))).contains("CODE_PAYLOAD", "TEST_PAYLOAD");
    var callIds =
        store.messages(id).stream()
            .flatMap(m -> m.toolCalls().stream())
            .map(AgentMessage.ToolCall::id)
            .toList();
    var replyIds =
        store.messages(id).stream()
            .flatMap(m -> m.toolResults().stream())
            .map(AgentMessage.ToolReply::id)
            .toList();
    assertThat(replyIds).containsExactlyElementsOf(callIds).hasSize(6);
    assertThat(plans.snapshot(id).steps()).allMatch(step -> step.status().equals("COMPLETED"));
    assertThat(JsonPath.<Integer>read(response.body(), "$.run.report.providerInputTokens"))
        .isEqualTo(32);
    assertThat(JsonPath.<Integer>read(response.body(), "$.run.report.providerOutputTokens"))
        .isEqualTo(12);
    assertThat(report.frames().getLast().projectedEstimatedTokens()).isNotEqualTo(32);
    assertThat(get("/api/context/runs/" + id + "/report").body())
        .contains("projectedEstimatedTokens", "compactedExchanges");
    for (String request : REQUESTS) assertWireBalanced(request);
    assertThat(store.conversation(conversation).turns()).hasSize(1);
  }

  @Test
  void budgetBelowProtectedMinimumHasNoModelIdentityOrToolCallsAndReleasesConversation()
      throws Exception {
    UUID conversation = store.createConversation().id();
    var response = run(conversation, 1, "Задача и запреты должны сохраниться полностью");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.run.stopReason"))
        .isEqualTo("CONTEXT_LIMIT");
    assertThat(REQUESTS).isEmpty();
    assertThat(TAGS).hasValue(0);
    assertThat(JsonPath.<Integer>read(response.body(), "$.run.report.modelCalls")).isZero();
    assertThat(JsonPath.<Boolean>read(response.body(), "$.context.frames[0].admitted")).isFalse();
    assertThat(store.conversation(conversation).activeRunId()).isNull();
    assertThat(store.conversation(conversation).turns()).isEmpty();
  }

  @Test
  void compactionCannotTurnUnfinishedPlanIntoSuccessfulFinalAnswer() throws Exception {
    MODE.set("INCOMPLETE");
    UUID conversation = store.createConversation().id();
    var response = run(conversation, 5000, "Не принимай обещание модели за завершённый план");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.run.stopReason"))
        .as(response.body())
        .isEqualTo("PLAN_INCOMPLETE");
    UUID id = UUID.fromString(JsonPath.read(response.body(), "$.run.id"));
    assertThat(runtime.report(id).frames())
        .as(runtime.report(id).toString())
        .anyMatch(frame -> frame.compactedExchanges() > 0);
    assertThat(JsonPath.<Object>read(response.body(), "$.run.answer")).isNull();
    assertThat(plans.snapshot(id).steps()).allMatch(step -> step.status().equals("PENDING"));
    assertThat(store.conversation(conversation).turns()).isEmpty();
    assertThat(REQUESTS.getLast()).contains("CURRENT_PLAN_V1", "PENDING");
  }

  @Test
  void secondRunPreservesOldUserConstraintsAndAssistantHypothesesWhileProjectingOldToolPayloads()
      throws Exception {
    UUID conversation = store.createConversation().id();
    var first = run(conversation, 32768, "OLDER_USER_CONSTRAINT: preserve public API");
    assertThat(JsonPath.<String>read(first.body(), "$.run.status")).isEqualTo("COMPLETED");
    UUID firstId = UUID.fromString(JsonPath.read(first.body(), "$.run.id"));
    String rawBefore = AgentJson.encode(store.messages(firstId));
    REQUESTS.clear();
    TAGS.set(0);
    var second = run(conversation, 6500, "NEW_USER_GOAL: investigate again");
    assertThat(JsonPath.<String>read(second.body(), "$.run.status"))
        .as(second.body())
        .isEqualTo("COMPLETED");
    assertThat(REQUESTS.getFirst())
        .contains("OLDER_USER_CONSTRAINT", "UNVERIFIED_FINAL_HYPOTHESIS", "NEW_USER_GOAL");
    assertThat(REQUESTS.getFirst()).doesNotContain("CODE_PAYLOAD");
    assertThat(AgentJson.encode(store.messages(firstId))).isEqualTo(rawBefore);
    assertThat(store.conversation(conversation).turns()).hasSize(2);
  }

  @Test
  void closedInputRejectsFakePermissionsUsageAndInvalidBudgetsBeforeStartingRun() throws Exception {
    UUID conversation = store.createConversation().id();
    var body = request(5500, "QUESTION");
    body.put("approved", true);
    assertThat(
            post("/api/context/conversations/" + conversation + "/runs", AgentJson.encode(body))
                .statusCode())
        .isEqualTo(400);
    body = request(5500, "QUESTION");
    body.put("maxInputTokens", 1.5);
    assertThat(
            post("/api/context/conversations/" + conversation + "/runs", AgentJson.encode(body))
                .statusCode())
        .isEqualTo(400);
    assertThat(run(conversation, 0, "QUESTION").statusCode()).isEqualTo(400);
    assertThat(post("/api/context/conversations/" + conversation + "/runs", "{").statusCode())
        .isEqualTo(400);
    assertThat(run(UUID.randomUUID(), 5500, "QUESTION").statusCode()).isEqualTo(404);
    assertThat(REQUESTS).isEmpty();
    assertThat(store.conversation(conversation).activeRunId()).isNull();
  }

  private HttpResponse<String> run(UUID conversation, int budget, String question)
      throws Exception {
    return post(
        "/api/context/conversations/" + conversation + "/runs",
        AgentJson.encode(request(budget, question)));
  }

  private static Map<String, Object> request(int budget, String question) {
    var body = new LinkedHashMap<String, Object>();
    body.put("question", question);
    body.put("mode", "ASK");
    body.put("skills", List.of());
    body.put("maxInputTokens", budget);
    body.put(
        "steps",
        List.of(
            step("code", "READ_CODE", CODE, List.of()),
            step("test", "READ_TEST", TEST, List.of("code"))));
    return body;
  }

  private static Map<String, Object> step(
      String id, String criterion, String path, List<String> dependencies) {
    return Map.of(
        "id",
        id,
        "title",
        "Read exact " + id,
        "criterion",
        criterion,
        "path",
        path,
        "startLine",
        1,
        "endLine",
        1,
        "dependsOn",
        dependencies,
        "required",
        true);
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return send(path, body);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(path, null);
  }

  private HttpResponse<String> send(String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(25))
              .header("Content-Type", "application/json")
              .method(
                  body == null ? "GET" : "POST",
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static Path temporary() {
    try {
      var root = Files.createTempDirectory("context-http-");
      Files.createDirectories(root.resolve(".agents/skills"));
      return root;
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
            int phase = REQUESTS.size() - 1;
            var calls = new ArrayList<Map<String, Object>>();
            if (MODE.get().equals("COMPLETE"))
              switch (phase) {
                case 0 -> {
                  calls.add(tool("startPlanStep", Map.of("stepId", "code"), 1));
                  calls.add(
                      tool("readFile", Map.of("path", CODE, "startLine", 1, "endLine", 1), 2));
                }
                case 1 -> {
                  calls.add(tool("completePlanStep", Map.of("stepId", "code"), 3));
                  calls.add(tool("startPlanStep", Map.of("stepId", "test"), 4));
                  calls.add(
                      tool("readFile", Map.of("path", TEST, "startLine", 1, "endLine", 1), 5));
                }
                case 2 -> calls.add(tool("completePlanStep", Map.of("stepId", "test"), 6));
              }
            else
              switch (phase) {
                case 0 ->
                    calls.add(
                        tool("readFile", Map.of("path", CODE, "startLine", 1, "endLine", 1), 1));
                case 1 ->
                    calls.add(
                        tool("readFile", Map.of("path", TEST, "startLine", 1, "endLine", 1), 2));
                case 2 -> calls.add(tool("planStatus", Map.of(), 3));
              }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put(
                "content",
                calls.isEmpty()
                    ? "UNVERIFIED_FINAL_HYPOTHESIS: нужно проверить overflow. Тесты не запускались."
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
    return Map.of("id", "context-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  private static void respond(HttpExchange x, Object body) throws IOException {
    byte[] bytes = AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(200, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }

  private static void assertWireBalanced(String request) {
    List<Map<String, Object>> messages = JsonPath.read(request, "$.messages");
    int pending = 0;
    for (var message : messages) {
      if (message.get("role").equals("assistant")
          && message.get("tool_calls") instanceof List<?> calls
          && !calls.isEmpty()) {
        assertThat(pending).isZero();
        pending = calls.size();
      } else if (message.get("role").equals("tool")) {
        assertThat(pending).isPositive();
        pending--;
      } else assertThat(pending).isZero();
    }
    assertThat(pending).isZero();
  }
}
