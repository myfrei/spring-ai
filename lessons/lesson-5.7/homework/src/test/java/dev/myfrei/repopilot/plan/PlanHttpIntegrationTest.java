package dev.myfrei.repopilot.plan;

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

/** Actual Boot HTTP -> Ollama HTTP fixture -> plan tools + actual filesystem receipts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(30)
class PlanHttpIntegrationTest {
  static final String CODE = PlanSessionTest.CODE, TEST = PlanSessionTest.TEST;
  static final Path ROOT = temporary();
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final AtomicReference<String> MODE = new AtomicReference<>("FULL");
  static final HttpServer OLLAMA = server();
  @LocalServerPort int port;
  @Autowired AgentStore store;
  @Autowired PlanRuntime runtime;
  @Autowired List<AgentTool> originalTools;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("repopilot.agent.enabled", () -> true);
    p.add("repopilot.workspace.enabled", () -> true);
    p.add("repopilot.controlled.enabled", () -> true);
    p.add("repopilot.skills.enabled", () -> true);
    p.add("repopilot.plans.enabled", () -> true);
    p.add("repopilot.workspace.root", ROOT::toString);
    p.add("repopilot.skills.root", () -> ROOT.resolve(".agents/skills").toString());
    p.add("repopilot.rag.enabled", () -> false);
    p.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    p.add("spring.ai.ollama.chat.model", () -> "test-model");
    p.add("repopilot.context.window-tokens", () -> 16384);
  }

  @BeforeEach
  void prepare() throws Exception {
    Files.createDirectories(ROOT.resolve("src/main/java"));
    Files.createDirectories(ROOT.resolve("src/test/java"));
    Files.createDirectories(ROOT.resolve(".agents/skills"));
    Files.writeString(ROOT.resolve(CODE), "class Calc {\n int value = 1;\n}\n");
    Files.writeString(ROOT.resolve(TEST), "class CalcTest {\n // test\n}\n");
    MODE.set("FULL");
    REQUESTS.clear();
  }

  @AfterAll
  static void close() throws Exception {
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
    try (var paths = Files.walk(ROOT)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  @Test
  void completePlanUsesSameRunReceiptsAndFreshReminderAtEveryCallBeforeOneCommit()
      throws Exception {
    String conversation = conversation();
    var result = run(conversation, body());
    assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    String id = JsonPath.read(result.body(), "$.run.id");
    assertThat(JsonPath.<String>read(result.body(), "$.run.status")).isEqualTo("COMPLETED");
    assertThat(JsonPath.<List<String>>read(result.body(), "$.plan.steps[*].status"))
        .containsExactly("COMPLETED", "COMPLETED");
    assertThat(JsonPath.<List<Boolean>>read(result.body(), "$.plan.steps[*].evidenceCurrent"))
        .containsExactly(true, true);
    assertThat(REQUESTS).hasSize(3);
    assertThat(JsonPath.<List<String>>read(REQUESTS.getFirst(), "$.tools[*].function.name"))
        .hasSize(12)
        .contains("startPlanStep", "completePlanStep", "revisePlanStep", "activateSkill");
    assertThat(originalTools).hasSize(8);
    assertThat(system(REQUESTS.getFirst()))
        .contains("CURRENT_PLAN_V1", "\"revision\":1", "PENDING");
    assertThat(system(REQUESTS.get(1))).contains("\"revision\":3", "COMPLETED", "PENDING");
    assertThat(system(REQUESTS.getLast()))
        .contains("\"revision\":5")
        .doesNotContain("\"status\":\"PENDING\"");
    assertThat(store.conversation(UUID.fromString(conversation)).turns()).hasSize(1);
    var messages = store.messages(UUID.fromString(id));
    assertThat(messages.stream().flatMap(m -> m.toolCalls().stream()).map(c -> c.id()).toList())
        .containsExactlyElementsOf(
            messages.stream().flatMap(m -> m.toolResults().stream()).map(r -> r.id()).toList());
    assertThat(get("/api/plans/runs/" + id).body()).contains("COMPLETED", "evidenceHash");
  }

  @Test
  void convincingFinalTextCannotCommitAnUnfinishedPlanAndNextRunStartsClean() throws Exception {
    MODE.set("PREMATURE");
    String conversation = conversation();
    var result = run(conversation, body());
    assertThat(JsonPath.<String>read(result.body(), "$.run.status")).isEqualTo("STOPPED");
    assertThat(JsonPath.<String>read(result.body(), "$.run.stopReason"))
        .isEqualTo("PLAN_INCOMPLETE");
    assertThat(JsonPath.<Object>read(result.body(), "$.run.answer")).isNull();
    String id = JsonPath.read(result.body(), "$.run.id");
    assertThat(store.messages(UUID.fromString(id))).hasSize(1);
    assertThat(store.conversation(UUID.fromString(conversation)).turns()).isEmpty();
    assertThat(store.conversation(UUID.fromString(conversation)).activeRunId()).isNull();
    MODE.set("FULL");
    REQUESTS.clear();
    assertThat(JsonPath.<String>read(run(conversation, body()).body(), "$.run.status"))
        .isEqualTo("COMPLETED");
    assertThat(REQUESTS.getFirst()).doesNotContain("All obligations are finished");
  }

  @Test
  void completionWithoutReadAndDependencyOutOfOrderDoNotBecomeSuccessfulSteps() throws Exception {
    MODE.set("FAKE");
    var result = run(conversation(), body());
    assertThat(JsonPath.<String>read(result.body(), "$.run.stopReason"))
        .isEqualTo("PLAN_INCOMPLETE");
    assertThat(REQUESTS.getLast()).contains("PLAN_DEPENDENCY_PENDING", "PLAN_NOT_READ");
    assertThat(JsonPath.<List<String>>read(result.body(), "$.plan.steps[*].status"))
        .containsExactly("ACTIVE", "PENDING");
  }

  @Test
  void physicalChangeAfterCompletionBlocksFinalSuccessAndShowsStaleEvidence() throws Exception {
    MODE.set("STALE");
    String conversation = conversation();
    var result = run(conversation, body());
    assertThat(JsonPath.<String>read(result.body(), "$.run.stopReason"))
        .isEqualTo("PLAN_EVIDENCE_STALE");
    assertThat(JsonPath.<Boolean>read(result.body(), "$.plan.steps[0].evidenceCurrent")).isFalse();
    assertThat(store.conversation(UUID.fromString(conversation)).turns()).isEmpty();
    assertThat(Files.readString(ROOT.resolve(CODE))).isEqualTo("// human change\n");
  }

  @Test
  void changedDependencyBlocksCompletionEvenIfItsOldStatusSaysCompleted() throws Exception {
    MODE.set("DEPENDENCY_STALE");
    var result = run(conversation(), body());
    assertThat(JsonPath.<String>read(result.body(), "$.run.stopReason"))
        .isEqualTo("PLAN_INCOMPLETE");
    assertThat(REQUESTS.getLast()).contains("PLAN_DEPENDENCY_STALE");
    assertThat(JsonPath.<List<String>>read(result.body(), "$.plan.steps[*].status"))
        .containsExactly("COMPLETED", "PENDING");
  }

  @Test
  void modelCannotRemoveRequiredCriterionOrRetargetItWithRevisionArguments() throws Exception {
    MODE.set("FORGED_REVISION");
    var result = run(conversation(), body());
    assertThat(JsonPath.<String>read(result.body(), "$.run.stopReason"))
        .isEqualTo("TOOL_ARGUMENTS");
    assertThat(JsonPath.<String>read(result.body(), "$.plan.steps[0].path")).isEqualTo(CODE);
    assertThat(JsonPath.<Boolean>read(result.body(), "$.plan.steps[0].required")).isTrue();
  }

  @Test
  void invalidClosedRequestsAndUnknownRunFailBeforeProvider() throws Exception {
    String conversation = conversation();
    String valid = AgentJson.encode(body());
    for (String raw :
        List.of(
            valid.substring(0, valid.length() - 1) + ",\"approved\":true}",
            valid.replace("\"startLine\":1", "\"startLine\":1.5"),
            valid.replace("\"criterion\":\"READ_CODE\"", "\"criterion\":\"RUN_TEST\""),
            "{\"question\":\"Q\",\"question\":\"again\"}")) {
      var result = post("/api/plans/conversations/" + conversation + "/runs", raw);
      assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
    }
    assertThat(get("/api/plans/runs/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    assertThat(REQUESTS).isEmpty();
  }

  static Map<String, Object> body() {
    return Map.of(
        "question",
        "Read source and test; finish both plan steps.",
        "mode",
        "ASK",
        "steps",
        List.of(
            step("code", "READ_CODE", CODE, List.of()),
            step("test", "READ_TEST", TEST, List.of("code"))));
  }

  static Map<String, Object> step(String id, String criterion, String path, List<String> deps) {
    return Map.of(
        "id",
        id,
        "title",
        "Read " + id,
        "criterion",
        criterion,
        "path",
        path,
        "startLine",
        1,
        "endLine",
        3,
        "dependsOn",
        deps,
        "required",
        true);
  }

  String conversation() throws Exception {
    return JsonPath.read(post("/api/agent/conversations", "{}").body(), "$.id");
  }

  HttpResponse<String> run(String conversation, Map<String, Object> body) throws Exception {
    return post("/api/plans/conversations/" + conversation + "/runs", AgentJson.encode(body));
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    try (var c = HttpClient.newHttpClient()) {
      return c.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  HttpResponse<String> get(String path) throws Exception {
    try (var c = HttpClient.newHttpClient()) {
      return c.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  static String system(String request) {
    List<Map<String, Object>> messages = JsonPath.read(request, "$.messages");
    return (String)
        messages.stream()
            .filter(m -> m.get("role").equals("system"))
            .findFirst()
            .orElseThrow()
            .get("content");
  }

  static Path temporary() {
    try {
      Path root = Files.createTempDirectory("plan-http-");
      Files.createDirectories(root.resolve(".agents/skills"));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(EXECUTOR);
      server.createContext(
          "/api/tags",
          x ->
              respond(
                  x,
                  Map.of(
                      "models", List.of(Map.of("name", "test-model", "digest", "a".repeat(64))))));
      server.createContext(
          "/api/chat",
          x -> {
            String request = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(request);
            List<Map<String, Object>> messages = JsonPath.read(request, "$.messages");
            var replies = messages.stream().filter(m -> m.get("role").equals("tool")).toList();
            int count = replies.size();
            var calls = new ArrayList<Map<String, Object>>();
            String mode = MODE.get();
            if (mode.equals("FORGED_REVISION") && count == 0)
              calls.add(
                  tool(
                      "revisePlanStep",
                      Map.of("stepId", "code", "reason", "skip", "path", TEST, "required", false),
                      1));
            else if (mode.equals("FAKE") && count == 0) {
              calls.add(tool("startPlanStep", Map.of("stepId", "test"), 1));
              calls.add(tool("startPlanStep", Map.of("stepId", "code"), 2));
              calls.add(tool("completePlanStep", Map.of("stepId", "code"), 3));
            } else if (!mode.equals("PREMATURE")
                && !mode.equals("FAKE")
                && !mode.equals("FORGED_REVISION")) {
              if (count == 0) {
                calls.add(tool("startPlanStep", Map.of("stepId", "code"), 1));
                calls.add(tool("readFile", Map.of("path", CODE, "startLine", 1, "endLine", 3), 2));
                calls.add(tool("completePlanStep", Map.of("stepId", "code"), 3));
              } else if (count == 3) {
                if (mode.equals("DEPENDENCY_STALE"))
                  Files.writeString(ROOT.resolve(CODE), "// dependency changed\n");
                calls.add(tool("startPlanStep", Map.of("stepId", "test"), 4));
                calls.add(tool("readFile", Map.of("path", TEST, "startLine", 1, "endLine", 3), 5));
                calls.add(tool("completePlanStep", Map.of("stepId", "test"), 6));
              } else if (count == 6 && mode.equals("STALE"))
                Files.writeString(ROOT.resolve(CODE), "// human change\n");
            }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put("content", calls.isEmpty() ? "All obligations are finished." : "");
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

  static Map<String, Object> tool(String name, Map<String, Object> args, int id) {
    return Map.of("id", "plan-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  static void respond(HttpExchange x, Object value) throws IOException {
    byte[] bytes = AgentJson.encode(value).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(200, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }
}
