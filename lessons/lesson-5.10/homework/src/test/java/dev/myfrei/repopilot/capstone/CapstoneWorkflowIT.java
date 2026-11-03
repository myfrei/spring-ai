package dev.myfrei.repopilot.capstone;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.ui.SessionCoordinator;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/**
 * Actual independent MCP process + Boot host + SDK gateway + Docker verifier + Ollama HTTP fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(120)
class CapstoneWorkflowIT {
  static final Path TEMP = temporary(), WORKSPACE = TEMP.resolve("workspace");
  static final String TOKEN = "capstone-integration-token",
      SOURCE = "src/main/java/dev/myfrei/fixture/StockLedger.java";
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final Map<String, Integer> PHASES = new ConcurrentHashMap<>();
  static final List<String> MODEL_REQUESTS = new CopyOnWriteArrayList<>();
  static final HttpServer OLLAMA = provider();
  static final int MCP_PORT = freePort();
  static final Process MCP = startMcp();
  @LocalServerPort int port;
  @Autowired SessionCoordinator sessions;

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
            "research",
            "sessions",
            "capstone")) props.add("repopilot." + flag + ".enabled", () -> true);
    props.add("repopilot.workspace.root", () -> WORKSPACE.toString());
    props.add("repopilot.skills.root", () -> WORKSPACE.resolve(".agents/skills").toString());
    props.add("repopilot.capstone.mcp-url", () -> "http://127.0.0.1:" + MCP_PORT);
    props.add("STOCKROOM_MCP_TOKEN", () -> TOKEN);
    props.add("repopilot.verification.image-id", () -> System.getProperty("verification.image"));
    props.add("repopilot.rag.enabled", () -> false);
    props.add("repopilot.context.window-tokens", () -> 24000);
    props.add(
        "spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    props.add("spring.ai.ollama.chat.model", () -> "test-model");
  }

  @AfterAll
  static void shutdown() throws Exception {
    MCP.destroy();
    if (!MCP.waitFor(10, TimeUnit.SECONDS)) {
      MCP.destroyForcibly();
      MCP.waitFor();
    }
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
  }

  @Test
  void realMcpFailureHumanPatchFreshPassingRunAndReviewProduceDoneOnlyAfterEveryGate()
      throws Exception {
    assertThat(System.getProperty("verification.image")).matches("sha256:[0-9a-f]{64}");
    var created = post("/api/capstone/sessions", Map.of("taskId", "STOCK-001"));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    String exercise = JsonPath.read(created.body(), "$.id"),
        conversation = JsonPath.read(created.body(), "$.conversationId");
    assertThat(JsonPath.<String>read(created.body(), "$.task.id")).isEqualTo("STOCK-001");
    assertThat(JsonPath.<String>read(created.body(), "$.importReceipt.protocolVersion"))
        .isEqualTo("2025-11-25");
    assertThat(JsonPath.<Integer>read(created.body(), "$.importReceipt.toolCalls")).isEqualTo(1);
    assertThat(get("/api/capstone/sessions/" + exercise + "/report").body())
        .contains("INCOMPLETE", "MCP_IMPORTED");
    assertThat(post("/api/capstone/sessions", Map.of("taskId", "STOCK-001")).body())
        .isEqualTo(created.body());
    assertThat(post("/api/capstone/sessions", Map.of("taskId", "TASK-101")).statusCode())
        .isEqualTo(400);
    assertThat(
            post("/api/capstone/sessions", Map.of("taskId", "STOCK-001", "approved", true))
                .statusCode())
        .isEqualTo(400);
    String foreign = JsonPath.read(post("/api/agent/conversations", Map.of()).body(), "$.id");
    var foreignFail = verify(foreign);
    assertThat(foreignFail.status).isEqualTo("FAIL");
    assertThat(
            post(
                    "/api/capstone/sessions/" + exercise + "/prepare",
                    Map.of("requestId", UUID.randomUUID(), "baselineReportId", foreignFail.id))
                .statusCode())
        .isEqualTo(409);
    var failure = verify(conversation);
    assertThat(failure.status).isEqualTo("FAIL");
    assertThat(failure.exitCode).isEqualTo(10);
    var requestId = UUID.randomUUID();
    var preparing =
        post(
            "/api/capstone/sessions/" + exercise + "/prepare",
            Map.of("requestId", requestId, "baselineReportId", failure.id));
    assertThat(preparing.statusCode()).as(preparing.body()).isEqualTo(200);
    String prepareId = JsonPath.read(preparing.body(), "$.id");
    var prepared = await(prepareId);
    assertThat(JsonPath.<String>read(prepared, "$.result.run.status"))
        .as(prepared)
        .isEqualTo("COMPLETED");
    assertThat(JsonPath.<String>read(prepared, "$.result.verification.state"))
        .isEqualTo("CHECKED_FAIL");
    int beforeRetry = MODEL_REQUESTS.size();
    assertThat(
            JsonPath.<String>read(
                post(
                        "/api/capstone/sessions/" + exercise + "/prepare",
                        Map.of("requestId", requestId, "baselineReportId", failure.id))
                    .body(),
                "$.id"))
        .isEqualTo(prepareId);
    assertThat(MODEL_REQUESTS).hasSize(beforeRetry);
    String run = JsonPath.read(prepared, "$.result.run.id");
    var proposalResponse = get("/api/controlled/runs/" + run + "/proposals");
    List<Map<String, Object>> proposals =
        AgentJson.parse(proposalResponse.body()) instanceof List<?> list
            ? (List<Map<String, Object>>) (List<?>) list
            : List.of();
    assertThat(proposals).hasSize(1);
    String patch = (String) proposals.getFirst().get("id");
    byte[] bad = Files.readAllBytes(WORKSPACE.resolve(SOURCE));
    assertThat(new String(bad, StandardCharsets.UTF_8)).contains("quantity>=available");
    assertThat(get("/api/capstone/sessions/" + exercise + "/report").body()).contains("INCOMPLETE");
    // A real PASS before the human applies this patch must not satisfy freshness after application.
    Files.writeString(
        WORKSPACE.resolve(SOURCE),
        new String(bad, StandardCharsets.UTF_8)
            .replace("quantity>=available", "quantity>available"));
    var oldPass = verify(conversation);
    assertThat(oldPass.status).isEqualTo("PASS");
    Files.write(WORKSPACE.resolve(SOURCE), bad);
    var applied =
        post("/api/controlled/proposals/" + patch + "/decision", Map.of("decision", "APPROVE"));
    assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(applied.body(), "$.status")).isEqualTo("APPLIED");
    assertThat(
            post("/api/controlled/proposals/" + patch + "/decision", Map.of("decision", "APPROVE"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            post(
                    "/api/capstone/sessions/" + exercise + "/review",
                    Map.of(
                        "requestId",
                        UUID.randomUUID(),
                        "patchId",
                        patch,
                        "passingReportId",
                        oldPass.id))
                .statusCode())
        .isEqualTo(409);
    var fresh = verify(conversation);
    assertThat(fresh.status).isEqualTo("PASS");
    assertThat(fresh.exitCode).isZero();
    var reviewing =
        post(
            "/api/capstone/sessions/" + exercise + "/review",
            Map.of("requestId", UUID.randomUUID(), "patchId", patch, "passingReportId", fresh.id));
    assertThat(reviewing.statusCode()).as(reviewing.body()).isEqualTo(200);
    String reviewId = JsonPath.read(reviewing.body(), "$.id");
    String reviewed = await(reviewId);
    assertThat(JsonPath.<String>read(reviewed, "$.result.run.status"))
        .as(reviewed)
        .isEqualTo("COMPLETED");
    var done = get("/api/capstone/sessions/" + exercise + "/report");
    assertThat(done.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(done.body(), "$.state")).as(done.body()).isEqualTo("DONE");
    assertThat(JsonPath.<List<Boolean>>read(done.body(), "$.gates[*].passed"))
        .hasSize(15)
        .containsOnly(true);
    assertThat(done.body())
        .contains("prepareEvidence", "reviewEvidence", "admission", "java-code-review", "@@")
        .doesNotContain(TOKEN);
    assertThat(JsonPath.<String>read(done.body(), "$.prepareEvidence.reads[0].runId"))
        .isEqualTo(run);
    assertThat(MODEL_REQUESTS).hasSize(6);
    Files.writeString(
        WORKSPACE.resolve(SOURCE), "\n// external edit after DONE\n", StandardOpenOption.APPEND);
    var stale = get("/api/capstone/sessions/" + exercise + "/report");
    assertThat(JsonPath.<String>read(stale.body(), "$.state")).isEqualTo("STALE");
  }

  private record Check(String id, String status, int exitCode) {}

  private Check verify(String conversation) throws Exception {
    var requested =
        post(
            "/api/verification/conversations/" + conversation + "/requests",
            Map.of("command", "STOCKROOM_CONTRACT_V1"));
    assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
    String request = JsonPath.read(requested.body(), "$.id");
    var decided =
        post("/api/verification/requests/" + request + "/decision", Map.of("decision", "APPROVE"));
    assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);
    var reports = get("/api/verification/conversations/" + conversation + "/reports");
    List<Map<String, Object>> list = (List<Map<String, Object>>) AgentJson.parse(reports.body());
    var report = list.getLast();
    return new Check(
        (String) report.get("id"), (String) report.get("status"), (Integer) report.get("exitCode"));
  }

  private String await(String job) throws Exception {
    for (int i = 0; i < 200; i++) {
      String body = get("/api/sessions/jobs/" + job).body();
      if (JsonPath.read(body, "$.result") != null) return body;
      Thread.sleep(30);
    }
    throw new AssertionError("job timeout");
  }

  private HttpResponse<String> post(String path, Object data) throws Exception {
    return send(path, data);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(path, null);
  }

  private HttpResponse<String> send(String path, Object data) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(45))
              .header("Content-Type", "application/json")
              .method(
                  data == null ? "GET" : "POST",
                  data == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(AgentJson.encode(data)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static Path temporary() {
    try {
      Path root = Files.createTempDirectory("capstone-workflow-");
      Path fixture = Path.of("capstone-workspace");
      try (var paths = Files.walk(fixture)) {
        for (Path path : paths.toList()) {
          if (path.toString().contains("/target/")) continue;
          Path to = root.resolve("workspace").resolve(fixture.relativize(path));
          if (Files.isDirectory(path)) Files.createDirectories(to);
          else Files.copy(path, to);
        }
      }
      return root;
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static int freePort() {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Process startMcp() {
    try {
      Path jar =
          Path.of(
                  System.getProperty(
                      "capstone.mcp.jar",
                      "capstone-task-server/target/stockroom-capstone-task-server-0.0.1-SNAPSHOT.jar"))
              .toAbsolutePath();
      assertThat(jar).exists();
      String java = Path.of(System.getProperty("java.home"), "bin/java").toString();
      var process =
          new ProcessBuilder(
                  java,
                  "-Xmx192m",
                  "-jar",
                  jar.toString(),
                  "--server.port=" + MCP_PORT,
                  "--stockroom.access-token=" + TOKEN)
              .redirectErrorStream(true)
              .redirectOutput(TEMP.resolve("mcp.log").toFile())
              .start();
      Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
      for (int i = 0; i < 160; i++) {
        if (!process.isAlive()) throw new AssertionError(Files.readString(TEMP.resolve("mcp.log")));
        try (var socket = new Socket()) {
          socket.connect(new InetSocketAddress("127.0.0.1", MCP_PORT), 100);
          return process;
        } catch (IOException waiting) {
          Thread.sleep(100);
        }
      }
      process.destroyForcibly();
      throw new AssertionError("MCP startup timeout");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer provider() {
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
            MODEL_REQUESTS.add(request);
            List<Map<String, Object>> messages = JsonPath.read(request, "$.messages");
            int last = 0;
            for (int i = 0; i < messages.size(); i++)
              if (messages.get(i).get("role").equals("user")) last = i;
            String question = (String) messages.get(last).get("content");
            int phase = PHASES.merge(question, 1, Integer::sum) - 1;
            boolean prepare = question.startsWith("CAPSTONE_PREPARE");
            String report =
                question.substring(question.indexOf("reportId=") + 9).split("[ .]", 2)[0];
            var calls = new ArrayList<Map<String, Object>>();
            if (phase == 0) {
              calls.add(tool("readVerification", Map.of("reportId", report), 1));
              calls.add(tool("startPlanStep", Map.of("stepId", "code"), 2));
              calls.add(
                  tool("readFile", Map.of("path", SOURCE, "startLine", 17, "endLine", 25), 3));
            } else if (phase == 1) {
              calls.add(tool("completePlanStep", Map.of("stepId", "code"), 4));
              if (prepare) {
                String envelope =
                    (String)
                        messages.subList(last, messages.size()).stream()
                            .filter(m -> m.get("role").equals("tool"))
                            .map(m -> (String) m.get("content"))
                            .filter(body -> body.contains("fullFileHash"))
                            .findFirst()
                            .orElseThrow();
                String hash = JsonPath.read(envelope, "$.data.fullFileHash");
                calls.add(
                    tool(
                        "proposePatch",
                        Map.of(
                            "path",
                            SOURCE,
                            "baseHash",
                            hash,
                            "oldText",
                            "if(quantity>=available)",
                            "newText",
                            "if(quantity>available)"),
                        5));
              }
            }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put(
                "content",
                calls.isEmpty()
                    ? "Проверен только фиксированный StockLedger contract; новые действия требуют"
                          + " отдельного решения."
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
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static Map<String, Object> tool(String name, Map<String, Object> args, int id) {
    return Map.of("id", "capstone-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  private static void respond(HttpExchange x, Object body) throws IOException {
    byte[] bytes = AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(200, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }
}
