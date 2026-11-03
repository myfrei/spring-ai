package dev.myfrei.repopilot.verification;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

/** Real Boot/Ollama HTTP; injected verification backend keeps default suite Docker-free. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VerificationHttpIntegrationTest.FakeBackend.class)
@ActiveProfiles("test")
@Timeout(30)
class VerificationHttpIntegrationTest {
  static final Path ROOT = temporary();
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final AtomicInteger EXECUTIONS = new AtomicInteger();
  static final AtomicReference<String> REPORT = new AtomicReference<>(),
      MODE = new AtomicReference<>("NORMAL");
  static final HttpServer OLLAMA = server();

  @TestConfiguration
  static class FakeBackend {
    @Bean
    @Primary
    VerificationService fakeVerification(WorkspaceService workspace, AgentStore store) {
      return new VerificationService(
          workspace,
          store,
          (image, path) -> {
            EXECUTIONS.incrementAndGet();
            return new VerificationRunner.Result(
                10, "exact-stock-reservation=FAIL", "", false, false, "CONTRACT_FAILED");
          },
          "sha256:" + "a".repeat(64),
          Clock.systemUTC());
    }
  }

  @LocalServerPort int port;
  @Autowired AgentStore store;
  @Autowired List<AgentTool> originalTools;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    for (String flag :
        List.of(
            "agent", "workspace", "controlled", "skills", "plans", "compaction", "verification"))
      p.add("repopilot." + flag + ".enabled", () -> true);
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
    Path target = ROOT.resolve(VerificationService.SOURCE);
    Files.createDirectories(target.getParent());
    Files.copy(
        Path.of("workspace-fixture").resolve(VerificationService.SOURCE),
        target,
        StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(
        target,
        "\n// Independent HTTP test " + UUID.randomUUID() + "\n",
        StandardOpenOption.APPEND);
    REQUESTS.clear();
    EXECUTIONS.set(0);
    MODE.set("NORMAL");
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
  void modelReadsActualReportInsideTheSamePlanSkillsContextLoopAndTypedOutcomeShowsFailure()
      throws Exception {
    String conversation = conversation();
    String report = verify(conversation);
    REPORT.set(report);
    var result =
        post("/api/verified/conversations/" + conversation + "/runs", AgentJson.encode(task()));
    assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(result.body(), "$.run.status")).isEqualTo("COMPLETED");
    assertThat(JsonPath.<String>read(result.body(), "$.verification.state"))
        .isEqualTo("CHECKED_FAIL");
    assertThat(JsonPath.<String>read(result.body(), "$.verification.reportId")).isEqualTo(report);
    assertThat(REQUESTS).hasSize(3);
    assertThat(JsonPath.<List<String>>read(REQUESTS.getFirst(), "$.tools[*].function.name"))
        .hasSize(13)
        .contains("readVerification", "activateSkill", "completePlanStep");
    assertThat(REQUESTS.get(1)).contains("exact-stock-reservation=FAIL", "CONTRACT_FAILED");
    assertThat(EXECUTIONS).hasValue(1);
    assertThat(originalTools).hasSize(8);
  }

  @Test
  void historicalPassOrFailureCannotRemainCurrentAfterAFileChange() throws Exception {
    String conversation = conversation();
    REPORT.set(verify(conversation));
    Files.writeString(
        ROOT.resolve(VerificationService.SOURCE),
        "\n// human changed\n",
        StandardOpenOption.APPEND);
    var result =
        post("/api/verified/conversations/" + conversation + "/runs", AgentJson.encode(task()));
    assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(result.body(), "$.verification.state")).isEqualTo("STALE");
    List<Map<String, Object>> messages = JsonPath.read(REQUESTS.get(1), "$.messages");
    String envelope =
        (String)
            messages.stream()
                .filter(m -> m.get("role").equals("tool"))
                .findFirst()
                .orElseThrow()
                .get("content");
    assertThat(JsonPath.<Boolean>read(envelope, "$.data.inputsStillCurrent")).isFalse();
    assertThat(EXECUTIONS).hasValue(1);
  }

  @Test
  void pendingPermissionNeverExecutesAndStrictInputRejectsCallerImageOrFakeApproval()
      throws Exception {
    String conversation = conversation();
    var pending =
        post(
            "/api/verification/conversations/" + conversation + "/requests",
            "{\"command\":\"STOCKROOM_CONTRACT_V1\"}");
    assertThat(pending.statusCode()).isEqualTo(200);
    assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
    String id = JsonPath.read(pending.body(), "$.id");
    assertThat(EXECUTIONS).hasValue(0);
    for (String body :
        List.of(
            "{\"decision\":\"APPROVE\",\"inputRevision\":\"fake\"}",
            "{\"decision\":\"APPROVE\",\"decision\":\"REJECT\"}",
            "{\"decision\":null}"))
      assertThat(post("/api/verification/requests/" + id + "/decision", body).statusCode())
          .isEqualTo(400);
    assertThat(
            post(
                    "/api/verification/conversations/" + conversation + "/requests",
                    "{\"command\":\"STOCKROOM_CONTRACT_V1\",\"imageId\":\"mutable\"}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            post("/api/verification/requests/" + id + "/decision", "{\"decision\":\"REJECT\"}")
                .body())
        .contains("REJECTED");
    assertThat(EXECUTIONS).hasValue(0);
  }

  @Test
  void modelHasNoApprovalToolAndCannotReadAnotherConversationsReport() throws Exception {
    String source = conversation();
    REPORT.set(verify(source));
    String other = conversation();
    var result = post("/api/verified/conversations/" + other + "/runs", AgentJson.encode(task()));
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(REQUESTS.get(1)).contains("VERIFICATION_SCOPE_DENIED");
    assertThat(JsonPath.<String>read(result.body(), "$.verification.state"))
        .isEqualTo("UNVERIFIED");
    assertThat(EXECUTIONS).hasValue(1);
  }

  static Map<String, Object> task() {
    return Map.of(
        "question",
        "Inspect actual report and source. Do not claim tests passed.",
        "mode",
        "ASK",
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
                VerificationService.SOURCE,
                "startLine",
                17,
                "endLine",
                22,
                "dependsOn",
                List.of(),
                "required",
                true)));
  }

  String verify(String conversation) throws Exception {
    var pending =
        post(
            "/api/verification/conversations/" + conversation + "/requests",
            "{\"command\":\"STOCKROOM_CONTRACT_V1\"}");
    assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
    String id = JsonPath.read(pending.body(), "$.id");
    assertThat(EXECUTIONS).hasValue(0);
    var done = post("/api/verification/requests/" + id + "/decision", "{\"decision\":\"APPROVE\"}");
    assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    assertThat(done.body()).contains("FAIL");
    return JsonPath.read(
        get("/api/verification/conversations/" + conversation + "/reports").body(), "$[0].id");
  }

  String conversation() throws Exception {
    return JsonPath.read(post("/api/agent/conversations", "{}").body(), "$.id");
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
      Path root = Files.createTempDirectory("verification-http-");
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
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(body);
            int phase = REQUESTS.size() - 1;
            var calls = new ArrayList<Map<String, Object>>();
            if (phase == 0) {
              calls.add(tool("readVerification", Map.of("reportId", REPORT.get()), 1));
              calls.add(tool("startPlanStep", Map.of("stepId", "code"), 2));
              calls.add(
                  tool(
                      "readFile",
                      Map.of("path", VerificationService.SOURCE, "startLine", 17, "endLine", 22),
                      3));
            } else if (phase == 1) calls.add(tool("completePlanStep", Map.of("stepId", "code"), 4));
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put(
                "content",
                calls.isEmpty() ? "Прочитан отчёт; исправление требует отдельного решения." : "");
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
    return Map.of(
        "id", "verification-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  static void respond(HttpExchange x, Object value) throws IOException {
    byte[] bytes = AgentJson.encode(value).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(200, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }
}
