package dev.myfrei.repopilot.research;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
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

/**
 * Real Boot/Ollama HTTP, separate child messages, actual workspace receipts, and concurrent cancel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(45)
class ResearchHttpIntegrationTest {
  static final Path ROOT = temporary();
  static final String CODE = "src/main/java/dev/myfrei/fixture/StockLedger.java";
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final List<String> PARENT_REQUESTS = new CopyOnWriteArrayList<>(),
      CHILD_REQUESTS = new CopyOnWriteArrayList<>();
  static final AtomicReference<String> MODE = new AtomicReference<>("COMPLETE");
  static final AtomicReference<CountDownLatch> CHILD_ENTERED = new AtomicReference<>(),
      CHILD_RELEASE = new AtomicReference<>();
  static final HttpServer OLLAMA = server();
  @LocalServerPort int port;
  @Autowired AgentStore store;
  @Autowired ResearchRuntime runtime;
  @Autowired WorkspaceService workspace;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry props) {
    for (String flag :
        List.of(
            "agent",
            "workspace",
            "controlled",
            "skills",
            "plans",
            "compaction",
            "verification",
            "research")) props.add("repopilot." + flag + ".enabled", () -> true);
    props.add("repopilot.workspace.root", () -> ROOT.toString());
    props.add("repopilot.skills.root", () -> ROOT.resolve(".agents/skills").toString());
    props.add("repopilot.verification.image-id", () -> "sha256:" + "a".repeat(64));
    props.add("repopilot.rag.enabled", () -> false);
    props.add("repopilot.context.window-tokens", () -> 24000);
    props.add(
        "spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    props.add("spring.ai.ollama.chat.model", () -> "test-model");
  }

  @BeforeEach
  void reset() throws Exception {
    Files.createDirectories(ROOT.resolve(CODE).getParent());
    Files.writeString(
        ROOT.resolve(CODE),
        "package dev.myfrei.fixture;\npublic class StockLedger { int quantity; }\n");
    MODE.set("COMPLETE");
    PARENT_REQUESTS.clear();
    CHILD_REQUESTS.clear();
    CHILD_ENTERED.set(new CountDownLatch(1));
    CHILD_RELEASE.set(new CountDownLatch(1));
  }

  @AfterEach
  void release() {
    CHILD_RELEASE.get().countDown();
  }

  @AfterAll
  static void stop() throws Exception {
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
    try (var paths = Files.walk(ROOT)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  @Test
  void childHasFreshReadOnlyContextAndParentRechecksUsingItsOwnReceiptsBeforeFinal()
      throws Exception {
    UUID conversation = store.createConversation().id();
    var response = run(conversation, ResearchLimits.DEFAULT);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.body(), "$.run.status"))
        .as(response.body())
        .isEqualTo("COMPLETED");
    assertThat(JsonPath.<String>read(response.body(), "$.verification.state"))
        .isEqualTo("UNVERIFIED");
    UUID parent = id(response);
    var snapshot = runtime.snapshot(parent);
    var child = snapshot.children().getFirst();
    assertThat(PARENT_REQUESTS).hasSize(3);
    assertThat(CHILD_REQUESTS).hasSize(2);
    assertThat(snapshot.budget().admittedModelCalls()).isEqualTo(5);
    assertThat(snapshot.budget().observedModelCalls()).isEqualTo(5);
    assertThat(snapshot.budget().providerInputTokens()).isEqualTo(40);
    assertThat(snapshot.budget().providerOutputTokens()).isEqualTo(15);
    assertThat(snapshot.budget().childrenStarted()).isEqualTo(1);
    assertThat(JsonPath.<List<String>>read(PARENT_REQUESTS.getFirst(), "$.tools[*].function.name"))
        .hasSize(14)
        .contains(
            "readVerification",
            "researchRepository",
            "proposePatch",
            "planStatus",
            "activateSkill");
    assertThat(JsonPath.<List<String>>read(CHILD_REQUESTS.getFirst(), "$.tools[*].function.name"))
        .containsExactlyInAnyOrder("listFiles", "searchText", "readFile");
    assertThat(CHILD_REQUESTS.getFirst())
        .contains("FOCUSED_CHILD_QUESTION")
        .doesNotContain(
            "PARENT_PRIVATE_GOAL",
            "CURRENT_PLAN_V1",
            "CURRENT_SKILL_CATALOG",
            "readVerification",
            "proposePatch");
    assertThat(child.status()).isEqualTo("CANDIDATE");
    assertThat(child.findings()).hasSize(1);
    assertThat(child.childRunId()).isNotEqualTo(parent);
    assertThat(workspace.receipts(child.childRunId().toString()))
        .allMatch(r -> r.runId().equals(child.childRunId().toString()));
    assertThat(workspace.receipts(parent.toString()))
        .allMatch(r -> r.runId().equals(parent.toString()));
    assertThat(
            get("/api/research/runs/" + parent + "/children/" + child.childRunId() + "/messages")
                .body())
        .contains("FOCUSED_CHILD_QUESTION")
        .doesNotContain("PARENT_PRIVATE_GOAL");
    assertThat(
            get("/api/research/runs/" + parent + "/children/" + UUID.randomUUID() + "/messages")
                .statusCode())
        .isEqualTo(404);
    assertThat(post("/api/research/runs/" + parent + "/cancel", "").statusCode()).isEqualTo(409);
    assertBalanced(store.messages(parent));
    assertBalanced(runtime.childMessages(parent, child.childRunId()));
  }

  @Test
  void childCitationDoesNotCompleteParentEvidenceEvenWhenParentPlanIsComplete() throws Exception {
    MODE.set("NO_RECHECK");
    var response = run(store.createConversation().id(), ResearchLimits.DEFAULT);
    assertThat(JsonPath.<String>read(response.body(), "$.run.stopReason"))
        .as(response.body())
        .isEqualTo("RESEARCH_NOT_RECHECKED");
    assertThat(JsonPath.<Object>read(response.body(), "$.run.answer")).isNull();
    var parent = id(response);
    assertThat(runtime.snapshot(parent).children().getFirst().status()).isEqualTo("CANDIDATE");
    assertThat(workspace.receipts(parent.toString())).allMatch(r -> r.endLine() == 1);
    assertThat(JsonPath.<List<String>>read(response.body(), "$.plan.steps[*].status"))
        .containsExactly("COMPLETED");
  }

  @Test
  void fabricatedChildVersionIsRejectedAndOnlyTypedFailureReachesParent() throws Exception {
    MODE.set("BAD_REPORT");
    var response = run(store.createConversation().id(), ResearchLimits.DEFAULT);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var child = runtime.snapshot(id(response)).children().getFirst();
    assertThat(child.status()).isEqualTo("REPORT_INVALID");
    assertThat(child.findings()).isEmpty();
    assertThat(PARENT_REQUESTS.getLast())
        .contains("RESEARCH_REPORT_INVALID")
        .doesNotContain("MALICIOUS_REPORT_STATEMENT");
  }

  @Test
  void childCannotUseWriteRunnerOrRecursiveDelegationTools() throws Exception {
    for (String escape : List.of("proposePatch", "readVerification", "researchRepository")) {
      MODE.set("ESCAPE:" + escape);
      PARENT_REQUESTS.clear();
      CHILD_REQUESTS.clear();
      var response = run(store.createConversation().id(), ResearchLimits.DEFAULT);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var snapshot = runtime.snapshot(id(response));
      assertThat(snapshot.children())
          .singleElement()
          .satisfies(
              child -> {
                assertThat(child.status()).isEqualTo("TOOL_NOT_ALLOWED");
                assertThat(child.findings()).isEmpty();
              });
      assertThat(snapshot.budget().childrenStarted()).isEqualTo(1);
      assertThat(CHILD_REQUESTS).hasSize(1);
      assertThat(Files.readString(ROOT.resolve(CODE))).contains("int quantity");
    }
  }

  @Test
  void sharedModelBudgetIncludesBothScopesAndStopsWithoutExtraProviderCall() throws Exception {
    var response = run(store.createConversation().id(), new ResearchLimits(2, 12, 48000));
    assertThat(JsonPath.<String>read(response.body(), "$.run.stopReason"))
        .as(response.body())
        .isEqualTo("RESEARCH_SHARED_MODEL_LIMIT");
    var snapshot = runtime.snapshot(id(response));
    assertThat(snapshot.budget().admittedModelCalls()).isEqualTo(2);
    assertThat(snapshot.budget().observedModelCalls()).isEqualTo(2);
    assertThat(PARENT_REQUESTS).hasSize(1);
    assertThat(CHILD_REQUESTS).hasSize(1);
    assertBalanced(store.messages(id(response)));
  }

  @Test
  void sharedToolAdmissionAndEstimatedReservationLimitAreHardAdmissionChecks() throws Exception {
    var limited = run(store.createConversation().id(), new ResearchLimits(8, 1, 48000));
    assertThat(JsonPath.<String>read(limited.body(), "$.run.stopReason"))
        .isEqualTo("RESEARCH_SHARED_TOOL_LIMIT");
    assertThat(CHILD_REQUESTS).isEmpty();
    assertThat(runtime.snapshot(id(limited)).budget().admittedToolRequests()).isEqualTo(1);
    assertBalanced(store.messages(id(limited)));
    PARENT_REQUESTS.clear();
    var reservation = run(store.createConversation().id(), new ResearchLimits(8, 12, 1));
    assertThat(JsonPath.<String>read(reservation.body(), "$.run.stopReason"))
        .isEqualTo("RESEARCH_RESERVATION_LIMIT");
    assertThat(PARENT_REQUESTS).isEmpty();
    assertThat(CHILD_REQUESTS).isEmpty();
    assertThat(runtime.snapshot(id(reservation)).budget().admittedModelCalls()).isZero();
  }

  @Test
  void parentCancelIsVisibleDuringChildRequestAndStopsChildBeforeItsReturnedToolExecutes()
      throws Exception {
    MODE.set("CANCEL");
    UUID conversation = store.createConversation().id();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = executor.submit(() -> run(conversation, ResearchLimits.DEFAULT));
      assertThat(CHILD_ENTERED.get().await(10, TimeUnit.SECONDS)).isTrue();
      UUID parent = store.conversation(conversation).activeRunId();
      assertThat(parent).isNotNull();
      var active = get("/api/research/runs/" + parent);
      assertThat(active.statusCode()).as(active.body()).isEqualTo(200);
      assertThat(JsonPath.<String>read(active.body(), "$.children[0].status")).isEqualTo("RUNNING");
      var cancelled = post("/api/research/runs/" + parent + "/cancel", "");
      assertThat(cancelled.statusCode()).isEqualTo(200);
      assertThat(JsonPath.<String>read(cancelled.body(), "$.status")).isEqualTo("CANCEL_REQUESTED");
      CHILD_RELEASE.get().countDown();
      var result = pending.get(15, TimeUnit.SECONDS);
      assertThat(JsonPath.<String>read(result.body(), "$.run.stopReason"))
          .as(result.body())
          .isEqualTo("CANCELLED");
      var child = runtime.snapshot(parent).children().getFirst();
      assertThat(child.status()).isEqualTo("CANCELLED");
      assertThat(runtime.childMessages(parent, child.childRunId())).hasSize(1);
      assertThat(PARENT_REQUESTS).hasSize(1);
      assertThat(CHILD_REQUESTS).hasSize(1);
      assertThat(store.conversation(conversation).turns()).isEmpty();
      assertThat(store.conversation(conversation).activeRunId()).isNull();
      assertBalanced(store.messages(parent));
    }
  }

  @Test
  void closedRequestsRejectFakeChildEvidenceDepthAndUnsupportedBudgets() throws Exception {
    UUID conversation = store.createConversation().id();
    var body = request(ResearchLimits.DEFAULT);
    body.put("childDepth", 2);
    assertThat(
            post("/api/research/conversations/" + conversation + "/runs", AgentJson.encode(body))
                .statusCode())
        .isEqualTo(400);
    assertThat(post("/api/research/conversations/" + conversation + "/runs", "{").statusCode())
        .isEqualTo(400);
    assertThat(get("/api/research/runs/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    assertThat(PARENT_REQUESTS).isEmpty();
    assertThat(CHILD_REQUESTS).isEmpty();
    assertThat(store.conversation(conversation).activeRunId()).isNull();
  }

  private HttpResponse<String> run(UUID conversation, ResearchLimits limits) throws Exception {
    return post(
        "/api/research/conversations/" + conversation + "/runs", AgentJson.encode(request(limits)));
  }

  private static Map<String, Object> request(ResearchLimits limits) {
    return new LinkedHashMap<>(
        Map.of(
            "question",
            "PARENT_PRIVATE_GOAL: изучи остаток и явно перепроверь цитату исследователя без записи",
            "mode",
            "ASK",
            "skills",
            List.of(),
            "steps",
            List.of(
                Map.of(
                    "id",
                    "code",
                    "title",
                    "Read package header",
                    "criterion",
                    "READ_CODE",
                    "path",
                    CODE,
                    "startLine",
                    1,
                    "endLine",
                    1,
                    "dependsOn",
                    List.of(),
                    "required",
                    true)),
            "maxInputTokens",
            16000,
            "researchBudget",
            limits));
  }

  private static UUID id(HttpResponse<String> response) {
    return UUID.fromString(JsonPath.read(response.body(), "$.run.id"));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(path, null);
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return send(path, body);
  }

  private HttpResponse<String> send(String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(30))
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
      Path root = Files.createTempDirectory("research-http-");
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
          x ->
              respond(
                  x,
                  Map.of(
                      "models", List.of(Map.of("name", "test-model", "digest", "a".repeat(64))))));
      server.createContext(
          "/api/chat",
          x -> {
            String request = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean child = request.contains("RESEARCH_CHILD_V1");
            var requests = child ? CHILD_REQUESTS : PARENT_REQUESTS;
            requests.add(request);
            int phase = requests.size() - 1;
            String mode = MODE.get();
            var calls = new ArrayList<Map<String, Object>>();
            String answer = "Исследование ограничено; тесты не запускались.";
            if (child) {
              if (mode.equals("CANCEL") && phase == 0) {
                CHILD_ENTERED.get().countDown();
                try {
                  if (!CHILD_RELEASE.get().await(12, TimeUnit.SECONDS))
                    throw new IOException("Fixture release timeout");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IOException(interrupted);
                }
              }
              if (phase == 0) {
                if (mode.startsWith("ESCAPE:")) calls.add(tool(mode.substring(7), Map.of(), 1));
                else
                  calls.add(
                      tool("readFile", Map.of("path", CODE, "startLine", 1, "endLine", 2), 1));
              } else {
                List<Map<String, Object>> messages = JsonPath.read(request, "$.messages");
                String envelope =
                    (String)
                        messages.stream()
                            .filter(m -> m.get("role").equals("tool"))
                            .findFirst()
                            .orElseThrow()
                            .get("content");
                String hash = JsonPath.read(envelope, "$.data.fullFileHash");
                answer =
                    AgentJson.encode(
                        Map.of(
                            "findings",
                            List.of(
                                Map.of(
                                    "statement",
                                    mode.equals("BAD_REPORT")
                                        ? "MALICIOUS_REPORT_STATEMENT"
                                        : "В прочитанном коде объявлено поле quantity",
                                    "path",
                                    CODE,
                                    "fullFileHash",
                                    mode.equals("BAD_REPORT") ? "b".repeat(64) : hash,
                                    "startLine",
                                    2,
                                    "endLine",
                                    2)),
                            "uncertainties",
                            List.of("Поведение не проверено запуском тестов")));
              }
            } else
              switch (phase) {
                case 0 -> {
                  calls.add(tool("startPlanStep", Map.of("stepId", "code"), 1));
                  calls.add(
                      tool(
                          "researchRepository",
                          Map.of(
                              "question",
                              "FOCUSED_CHILD_QUESTION: найди поле количества и процитируй реальный"
                                  + " код"),
                          2));
                }
                case 1 -> {
                  calls.add(
                      tool(
                          "readFile",
                          Map.of(
                              "path",
                              CODE,
                              "startLine",
                              1,
                              "endLine",
                              mode.equals("NO_RECHECK") ? 1 : 2),
                          3));
                  calls.add(tool("completePlanStep", Map.of("stepId", "code"), 4));
                }
              }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put("content", calls.isEmpty() ? answer : "");
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
    return Map.of("id", "scope-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  private static void respond(HttpExchange x, Object body) throws IOException {
    byte[] bytes = AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(200, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }

  private static void assertBalanced(List<AgentMessage> messages) {
    var calls =
        messages.stream()
            .flatMap(m -> m.toolCalls().stream())
            .map(AgentMessage.ToolCall::id)
            .toList();
    var replies =
        messages.stream()
            .flatMap(m -> m.toolResults().stream())
            .map(AgentMessage.ToolReply::id)
            .toList();
    assertThat(replies).containsExactlyElementsOf(calls);
  }
}
