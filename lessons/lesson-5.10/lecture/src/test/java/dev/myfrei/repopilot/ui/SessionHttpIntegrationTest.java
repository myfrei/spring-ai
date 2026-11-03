package dev.myfrei.repopilot.ui;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual Boot asynchronous jobs/long-poll commands around the unchanged real Ollama HTTP loop. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(30)
class SessionHttpIntegrationTest {
  static final String SOURCE = "src/main/java/dev/myfrei/fixture/StockLedger.java";
  static final Path ROOT = temporary();
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final AtomicReference<String> MODE = new AtomicReference<>("FULL");
  static volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
  static final HttpServer OLLAMA = server();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    for (String flag :
        List.of(
            "agent",
            "workspace",
            "controlled",
            "skills",
            "plans",
            "compaction",
            "verification",
            "research",
            "sessions")) p.add("repopilot." + flag + ".enabled", () -> true);
    p.add("repopilot.workspace.root", ROOT::toString);
    p.add("repopilot.skills.root", () -> ROOT.resolve(".agents/skills").toString());
    p.add("repopilot.verification.image-id", () -> "sha256:" + "a".repeat(64));
    p.add("repopilot.rag.enabled", () -> false);
    p.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    p.add("spring.ai.ollama.chat.model", () -> "test-model");
    p.add("repopilot.context.window-tokens", () -> 24000);
  }

  @BeforeEach
  void reset() throws Exception {
    Path target = ROOT.resolve(SOURCE);
    Files.createDirectories(target.getParent());
    Files.copy(
        Path.of("workspace-fixture").resolve(SOURCE), target, StandardCopyOption.REPLACE_EXISTING);
    MODE.set("FULL");
    REQUESTS.clear();
    entered = new CountDownLatch(1);
    release = new CountDownLatch(1);
  }

  @AfterEach
  void unblock() {
    release.countDown();
  }

  @AfterAll
  static void close() throws Exception {
    release.countDown();
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
    try (var paths = Files.walk(ROOT)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  @Test
  void submitReturnsWhileModelIsBlockedAndReplayIsReadOnlyIdempotentAndTerminalEventIsLast()
      throws Exception {
    UUID requestId = UUID.randomUUID();
    String conversation = conversation();
    long start = System.nanoTime();
    var submitted =
        post("/api/sessions/conversations/" + conversation + "/jobs", body(requestId, "Q", "ASK"));
    assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
    assertThat((System.nanoTime() - start) / 1e9).isLessThan(2);
    String id = JsonPath.read(submitted.body(), "$.id");
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(get("/api/sessions/jobs/" + id).body()).contains("RUNNING");
    var first = get("/api/sessions/jobs/" + id + "/events?after=0");
    long cursor = JsonPath.<Number>read(first.body(), "$.nextSequence").longValue();
    release.countDown();
    var completed = await(id);
    assertThat(JsonPath.<String>read(completed.body(), "$.status")).isEqualTo("COMPLETED");
    var replay = get("/api/sessions/jobs/" + id + "/events?after=" + cursor + "&waitMs=1000");
    assertThat(JsonPath.<Boolean>read(replay.body(), "$.terminal")).isTrue();
    List<String> types = JsonPath.read(replay.body(), "$.events[*].type");
    assertThat(types.getLast()).isEqualTo("SESSION_FINISHED");
    assertThat(REQUESTS).hasSize(2);
    for (int i = 0; i < 3; i++) {
      get("/api/sessions/jobs/" + id);
      get("/api/sessions/jobs/" + id + "/events?after=0");
    }
    var retried =
        post("/api/sessions/conversations/" + conversation + "/jobs", body(requestId, "Q", "ASK"));
    assertThat(JsonPath.<String>read(retried.body(), "$.id")).isEqualTo(id);
    assertThat(
            post(
                    "/api/sessions/conversations/" + conversation + "/jobs",
                    body(requestId, "Changed", "ASK"))
                .statusCode())
        .isEqualTo(409);
    assertThat(REQUESTS).hasSize(2);
  }

  @Test
  void disconnectingLongPollDoesNotCancelTheTaskOrReexecuteIt() throws Exception {
    String conversation = conversation();
    String id =
        JsonPath.read(
            post(
                    "/api/sessions/conversations/" + conversation + "/jobs",
                    body(UUID.randomUUID(), "Q", "ASK"))
                .body(),
            "$.id");
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    long cursor =
        JsonPath.<Number>read(get("/api/sessions/jobs/" + id).body(), "$.lastSequence").longValue();
    try (var client = HttpClient.newHttpClient()) {
      var watching =
          client.sendAsync(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + port
                              + "/api/sessions/jobs/"
                              + id
                              + "/events?after="
                              + cursor
                              + "&waitMs=10000"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      watching.cancel(true);
      release.countDown();
      var result = await(id);
      assertThat(JsonPath.<String>read(result.body(), "$.status")).isEqualTo("COMPLETED");
      assertThat(JsonPath.<Boolean>read(result.body(), "$.research.cancelled")).isFalse();
      assertThat(REQUESTS).hasSize(2);
    }
  }

  @Test
  void explicitCancelDiscardsLateToolRequestAndLeavesZeroExecutedTools() throws Exception {
    MODE.set("CANCEL");
    String conversation = conversation();
    String id =
        JsonPath.read(
            post(
                    "/api/sessions/conversations/" + conversation + "/jobs",
                    body(UUID.randomUUID(), "Q", "ASK"))
                .body(),
            "$.id");
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    var cancel = post("/api/sessions/jobs/" + id + "/commands", "{\"type\":\"CANCEL\"}");
    assertThat(cancel.statusCode()).isEqualTo(200);
    release.countDown();
    var result = await(id);
    assertThat(JsonPath.<String>read(result.body(), "$.status")).isEqualTo("STOPPED");
    assertThat(JsonPath.<String>read(result.body(), "$.stopReason")).isEqualTo("CANCELLED");
    assertThat(JsonPath.<Integer>read(result.body(), "$.result.run.report.executedTools")).isZero();
    assertThat(REQUESTS).hasSize(1);
  }

  @Test
  void patchPreviewUsesExactServerIdAndMutationIsExplicitScopedAndOneShot() throws Exception {
    MODE.set("PATCH");
    String conversation = conversation();
    String id =
        JsonPath.read(
            post(
                    "/api/sessions/conversations/" + conversation + "/jobs",
                    body(UUID.randomUUID(), "Q", "EDIT"))
                .body(),
            "$.id");
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    var done = await(id);
    assertThat(JsonPath.<String>read(done.body(), "$.status"))
        .as(done.body())
        .isEqualTo("COMPLETED");
    String approval = JsonPath.read(done.body(), "$.approvals[0].id");
    String before = Files.readString(ROOT.resolve(SOURCE));
    var detail = get("/api/sessions/jobs/" + id + "/approvals/PATCH/" + approval);
    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(detail.body())
        .contains("Quantity must be positive", "Quantity must be at least one", "baseHash");
    assertThat(Files.readString(ROOT.resolve(SOURCE))).isEqualTo(before);
    var rejected =
        post(
            "/api/sessions/jobs/" + id + "/commands",
            AgentJson.encode(Map.of("type", "REJECT_PATCH", "approvalId", approval)));
    assertThat(rejected.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<List<Object>>read(rejected.body(), "$.approvals")).isEmpty();
    assertThat(
            post(
                    "/api/sessions/jobs/" + id + "/commands",
                    AgentJson.encode(Map.of("type", "APPROVE_PATCH", "approvalId", approval)))
                .statusCode())
        .isEqualTo(409);
    assertThat(Files.readString(ROOT.resolve(SOURCE))).isEqualTo(before);
    assertThat(REQUESTS).hasSize(3);
  }

  @Test
  void verificationRequestAndRejectDoNotLaunchDockerAndClosedCommandsRejectForgedFields()
      throws Exception {
    String id =
        JsonPath.read(
            post(
                    "/api/sessions/conversations/" + conversation() + "/jobs",
                    body(UUID.randomUUID(), "Q", "ASK"))
                .body(),
            "$.id");
    entered.await(2, TimeUnit.SECONDS);
    release.countDown();
    await(id);
    var pending =
        post("/api/sessions/jobs/" + id + "/commands", "{\"type\":\"REQUEST_VERIFICATION\"}");
    assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
    String approval = JsonPath.read(pending.body(), "$.approvals[0].id");
    var detail = get("/api/sessions/jobs/" + id + "/approvals/VERIFICATION/" + approval);
    assertThat(detail.body()).contains("STOCKROOM_CONTRACT_V1", "sha256:", "PENDING");
    for (String raw :
        List.of(
            "{\"type\":\"CANCEL\",\"approved\":true}",
            "{\"type\":\"APPROVE_PATCH\"}",
            "{\"type\":\"CANCEL\",\"type\":\"REQUEST_VERIFICATION\"}"))
      assertThat(post("/api/sessions/jobs/" + id + "/commands", raw).statusCode()).isEqualTo(400);
    assertThat(
            post(
                    "/api/sessions/jobs/" + id + "/commands",
                    AgentJson.encode(Map.of("type", "REJECT_VERIFICATION", "approvalId", approval)))
                .statusCode())
        .isEqualTo(200);
  }

  static String body(UUID request, String question, String mode) {
    return AgentJson.encode(
        Map.of(
            "requestId",
            request.toString(),
            "task",
            Map.of(
                "question",
                question,
                "mode",
                mode,
                "maxInputTokens",
                16000,
                "steps",
                List.of(
                    Map.of(
                        "id",
                        "code",
                        "title",
                        "Read code",
                        "criterion",
                        "READ_CODE",
                        "path",
                        SOURCE,
                        "startLine",
                        17,
                        "endLine",
                        22,
                        "dependsOn",
                        List.of(),
                        "required",
                        true)))));
  }

  HttpResponse<String> await(String id) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < until) {
      var result = get("/api/sessions/jobs/" + id);
      String status = JsonPath.read(result.body(), "$.status");
      if (Set.of("COMPLETED", "STOPPED", "FAILED").contains(status)) return result;
      Thread.sleep(10);
    }
    throw new AssertionError("Job did not finish");
  }

  String conversation() throws Exception {
    var response = post("/api/agent/conversations", "{}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JsonPath.read(response.body(), "$.id");
  }

  HttpResponse<String> post(String path, String body) throws Exception {
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

  HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  static Path temporary() {
    try {
      Path root = Files.createTempDirectory("sessions-http-");
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
            String raw = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(raw);
            int phase = REQUESTS.size() - 1;
            if (phase == 0) {
              entered.countDown();
              try {
                if (!release.await(8, TimeUnit.SECONDS)) throw new IOException("Test timeout");
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            }
            var calls = new ArrayList<Map<String, Object>>();
            if (phase == 0) {
              calls.add(tool("startPlanStep", Map.of("stepId", "code"), 1));
              calls.add(
                  tool("readFile", Map.of("path", SOURCE, "startLine", 17, "endLine", 22), 2));
              if (!MODE.get().equals("PATCH"))
                calls.add(tool("completePlanStep", Map.of("stepId", "code"), 3));
            } else if (phase == 1 && MODE.get().equals("PATCH")) {
              List<Map<String, Object>> messages = JsonPath.read(raw, "$.messages");
              String hash = null;
              for (var m : messages)
                if (m.get("role").equals("tool")) {
                  var data = AgentJson.parse((String) m.get("content"));
                  if (data instanceof Map<?, ?> envelope
                      && envelope.get("data") instanceof Map<?, ?> content
                      && content.get("fullFileHash") instanceof String value) hash = value;
                }
              calls.add(tool("completePlanStep", Map.of("stepId", "code"), 3));
              calls.add(
                  tool(
                      "proposePatch",
                      Map.of(
                          "path",
                          SOURCE,
                          "baseHash",
                          hash,
                          "oldText",
                          "Quantity must be positive",
                          "newText",
                          "Quantity must be at least one"),
                      4));
            }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put("content", calls.isEmpty() ? "Код исследован; тесты не запускались." : "");
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
    return Map.of("id", "session-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  static void respond(HttpExchange x, Object body) throws IOException {
    byte[] bytes = AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(200, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }
}
