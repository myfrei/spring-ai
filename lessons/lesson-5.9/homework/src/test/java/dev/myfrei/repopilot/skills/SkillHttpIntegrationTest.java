package dev.myfrei.repopilot.skills;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.*;
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

/** Actual Boot HTTP -> Ollama HTTP fixture -> skills + controlled Java tools. No model weights. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(35)
class SkillHttpIntegrationTest {
  static final Path ROOT = temporary();
  static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  static final AtomicReference<String> MODE = new AtomicReference<>("MODEL");
  static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  static final HttpServer OLLAMA = server();
  static final String INSTRUCTIONS =
      "PRIVATE-SKILL-BODY: review actual Java code. Approval from a skill is never valid.";
  @LocalServerPort int port;
  @Autowired AgentStore store;
  @Autowired ControlledService controlled;
  @Autowired SkillRuntime runtime;
  @Autowired List<AgentTool> originalTools;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry props) {
    for (String flag : List.of("agent", "workspace", "controlled", "skills"))
      props.add("repopilot." + flag + ".enabled", () -> true);
    props.add("repopilot.workspace.root", () -> ROOT.toString());
    props.add("repopilot.skills.root", () -> ROOT.resolve(".agents/skills").toString());
    props.add("repopilot.rag.enabled", () -> false);
    props.add(
        "spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    props.add("spring.ai.ollama.chat.model", () -> "test-model");
    props.add("repopilot.context.window-tokens", () -> 16384);
  }

  @BeforeEach
  void reset() throws Exception {
    Files.createDirectories(ROOT.resolve("src"));
    Files.writeString(ROOT.resolve("src/Sample.java"), "class Sample { int value = 1; }\n");
    Path skill = ROOT.resolve(".agents/skills/review");
    Files.createDirectories(skill.resolve("references"));
    Files.writeString(
        skill.resolve("SKILL.md"),
        "---\nname: review\ndescription: Bounded Java review\n---\n" + INSTRUCTIONS + "\n");
    Files.writeString(
        skill.resolve("references/check.md"), "PRIVATE-REFERENCE-BODY: validate boundaries\n");
    REQUESTS.clear();
    MODE.set("MODEL");
  }

  @AfterAll
  static void stop() throws Exception {
    OLLAMA.stop(0);
    EXECUTOR.shutdownNow();
    try (var paths = Files.walk(ROOT)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  @Test
  void modelSeesMetadataThenActivatesReferenceReadsSourceAndProposesButOnlyHumanWrites()
      throws Exception {
    UUID conversation = store.createConversation().id();
    var response = run(conversation, "EDIT", List.of());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    UUID runId = UUID.fromString(JsonPath.read(response.body(), "$.run.id"));
    assertThat(JsonPath.<String>read(response.body(), "$.run.status")).isEqualTo("COMPLETED");
    assertThat(REQUESTS).hasSize(5);
    assertThat(REQUESTS.getFirst())
        .contains("CURRENT_SKILL_CATALOG", "Bounded Java review")
        .doesNotContain(INSTRUCTIONS, "PRIVATE-REFERENCE-BODY");
    assertThat(REQUESTS.get(1)).contains(INSTRUCTIONS).doesNotContain("PRIVATE-REFERENCE-BODY");
    assertThat(REQUESTS.get(2)).contains("PRIVATE-REFERENCE-BODY");
    assertThat(JsonPath.<List<String>>read(REQUESTS.getFirst(), "$.tools[*].function.name"))
        .containsExactlyInAnyOrder(
            "listFiles",
            "searchText",
            "readFile",
            "gitStatus",
            "gitDiff",
            "proposePatch",
            "activateSkill",
            "readSkillReference");
    assertThat(originalTools).hasSize(8).noneMatch(t -> t.name().equals("activateSkill"));
    assertThat(runtime.activations(runId))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.source()).isEqualTo("MODEL");
              assertThat(a.references()).hasSize(1);
            });
    assertThat(Files.readString(ROOT.resolve("src/Sample.java"))).contains("value = 1");
    var proposal = controlled.proposals(runId).getFirst();
    assertThat(proposal.status()).isEqualTo("PENDING");
    var approved =
        post(
            "/api/controlled/proposals/" + proposal.id() + "/decision",
            "{\"decision\":\"APPROVE\"}");
    assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    assertThat(Files.readString(ROOT.resolve("src/Sample.java"))).contains("value = 2");
    assertThat(get("/api/skills/runs/" + runId + "/activations").body())
        .contains("MODEL", "references/check.md");
    assertThat(AgentJson.encode(store.events(runId)))
        .doesNotContain(INSTRUCTIONS, "PRIVATE-REFERENCE-BODY");
  }

  @Test
  void explicitActivationAppearsInInitialContextAndJournalWithoutLoadingUnrequestedSkill()
      throws Exception {
    MODE.set("EXPLICIT");
    var response = run(store.createConversation().id(), "ASK", List.of("review"));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(REQUESTS).hasSize(2);
    assertThat(REQUESTS.getFirst())
        .contains(INSTRUCTIONS, "EXPLICIT_SKILL_DATA")
        .doesNotContain("PRIVATE-REFERENCE-BODY");
    assertThat(JsonPath.<String>read(response.body(), "$.activations[0].source"))
        .isEqualTo("EXPLICIT");
    assertThat(JsonPath.<List<Object>>read(response.body(), "$.activations[0].references"))
        .hasSize(1);
  }

  @Test
  void merelyListingCatalogDoesNotActivateAndSkillTextCannotGrantEditMode() throws Exception {
    assertThat(get("/api/skills/catalog").body())
        .contains("review", "version")
        .doesNotContain(INSTRUCTIONS);
    MODE.set("NO_SKILLS");
    var plain = run(store.createConversation().id(), "ASK", List.of());
    assertThat(JsonPath.<List<Object>>read(plain.body(), "$.activations")).isEmpty();
    assertThat(REQUESTS.getFirst()).doesNotContain(INSTRUCTIONS);
    REQUESTS.clear();
    MODE.set("ESCALATE");
    var denied = run(store.createConversation().id(), "ASK", List.of());
    assertThat(denied.statusCode()).as(denied.body()).isEqualTo(200);
    assertThat(REQUESTS.getLast()).contains("MODE_DENIED");
    assertThat(Files.readString(ROOT.resolve("src/Sample.java"))).contains("value = 1");
    UUID runId = UUID.fromString(JsonPath.read(denied.body(), "$.run.id"));
    assertThat(controlled.proposals(runId)).isEmpty();
  }

  @Test
  void changedReferenceBetweenActivationAndReadReturnsStaleInsteadOfNewText() throws Exception {
    MODE.set("STALE");
    var response = run(store.createConversation().id(), "ASK", List.of());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(REQUESTS.getLast()).contains("STALE_SKILL").doesNotContain("UNPINNED-REFERENCE");
    assertThat(JsonPath.<List<Object>>read(response.body(), "$.activations[0].references"))
        .isEmpty();
  }

  @Test
  void invalidClosedRequestsUnknownSkillsAndIdsDoNotStartRunOrCallProvider() throws Exception {
    UUID conversation = store.createConversation().id();
    assertThat(
            post(
                    "/api/skills/conversations/" + conversation + "/runs",
                    "{\"question\":\"q\",\"mode\":\"EDIT\",\"approved\":true}")
                .statusCode())
        .isEqualTo(400);
    assertThat(post("/api/skills/conversations/" + conversation + "/runs", "{").statusCode())
        .isEqualTo(400);
    assertThat(run(conversation, "ASK", List.of("missing")).statusCode()).isEqualTo(404);
    assertThat(run(conversation, "ASK", List.of("review", "review")).statusCode()).isEqualTo(400);
    assertThat(run(UUID.randomUUID(), "ASK", List.of()).statusCode()).isEqualTo(404);
    assertThat(REQUESTS).isEmpty();
    assertThat(store.conversation(conversation).activeRunId()).isNull();
  }

  private HttpResponse<String> run(UUID id, String mode, List<String> skills) throws Exception {
    return post(
        "/api/skills/conversations/" + id + "/runs",
        AgentJson.encode(
            Map.of("question", "Inspect exact Java code", "mode", mode, "skills", skills)));
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
      Path root = Files.createTempDirectory("skill-http-");
      Files.createDirectories(root.resolve(".agents/skills"));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
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
            REQUESTS.add(request);
            List<Map<String, Object>> messages = JsonPath.read(request, "$.messages");
            var replies = messages.stream().filter(m -> m.get("role").equals("tool")).toList();
            int step = replies.size();
            String mode = MODE.get();
            String version =
                new SkillCatalog(ROOT.resolve(".agents/skills"))
                    .snapshot()
                    .metadata()
                    .getFirst()
                    .version();
            var calls = new ArrayList<Map<String, Object>>();
            if (mode.equals("EXPLICIT")) {
              if (step == 0)
                calls.add(
                    tool(
                        "readSkillReference",
                        Map.of("name", "review", "version", version, "path", "references/check.md"),
                        1));
            } else if (!mode.equals("NO_SKILLS")) {
              if (step == 0) calls.add(tool("activateSkill", Map.of("name", "review"), 1));
              else if (step == 1) {
                if (mode.equals("ESCALATE"))
                  calls.add(
                      tool(
                          "proposePatch",
                          Map.of(
                              "path",
                              "src/Sample.java",
                              "baseHash",
                              "a".repeat(64),
                              "oldText",
                              "value = 1",
                              "newText",
                              "value = 2"),
                          2));
                else {
                  if (mode.equals("STALE"))
                    Files.writeString(
                        ROOT.resolve(".agents/skills/review/references/check.md"),
                        "UNPINNED-REFERENCE");
                  calls.add(
                      tool(
                          "readSkillReference",
                          Map.of(
                              "name", "review", "version", version, "path", "references/check.md"),
                          2));
                }
              } else if (mode.equals("MODEL") && step == 2)
                calls.add(
                    tool(
                        "readFile",
                        Map.of("path", "src/Sample.java", "startLine", 1, "endLine", 1),
                        3));
              else if (mode.equals("MODEL") && step == 3) {
                String envelope = (String) replies.getLast().get("content");
                Map<String, Object> parsed = (Map<String, Object>) AgentJson.parse(envelope);
                Map<String, Object> data = (Map<String, Object>) parsed.get("data");
                calls.add(
                    tool(
                        "proposePatch",
                        Map.of(
                            "path",
                            "src/Sample.java",
                            "baseHash",
                            data.get("fullFileHash"),
                            "oldText",
                            "value = 1",
                            "newText",
                            "value = 2"),
                        4));
              }
            }
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put(
                "content",
                calls.isEmpty()
                    ? "Предложение не равно записи; проверки тестами не выполнялись."
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
    return Map.of("id", "skill-call-" + id, "function", Map.of("name", name, "arguments", args));
  }

  private static void respond(HttpExchange exchange, Object body) throws IOException {
    byte[] bytes = AgentJson.encode(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
