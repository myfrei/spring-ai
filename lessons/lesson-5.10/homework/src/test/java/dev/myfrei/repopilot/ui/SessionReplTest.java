package dev.myfrei.repopilot.ui;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.*;
import dev.myfrei.repopilot.agent.AgentJson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class SessionReplTest {
  static final String CONVERSATION = "10000000-0000-0000-0000-000000000001",
      JOB = "20000000-0000-0000-0000-000000000001",
      SECOND = "20000000-0000-0000-0000-000000000002";
  HttpServer server;
  ExecutorService executor;
  List<String> requests;
  AtomicInteger models;
  AtomicBoolean expired;
  CountDownLatch watching, release;

  @BeforeEach
  void start() throws Exception {
    requests = new CopyOnWriteArrayList<>();
    models = new AtomicInteger();
    expired = new AtomicBoolean();
    watching = new CountDownLatch(1);
    release = new CountDownLatch(1);
    executor = Executors.newCachedThreadPool();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext(
        "/",
        x -> {
          String path = x.getRequestURI().toString();
          String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(x.getRequestMethod() + " " + path + " " + body);
          Object result;
          if (path.equals("/api/agent/conversations")) result = Map.of("id", CONVERSATION);
          else if (path.endsWith("/jobs") && x.getRequestMethod().equals("POST")) {
            models.incrementAndGet();
            result = snapshot(JOB);
          } else if (path.equals("/api/sessions/skills"))
            result = List.of(Map.of("name", "review", "description", "Read code"));
          else if (path.contains("/events")) {
            if (expired.getAndSet(false)) {
              respond(x, 409, Map.of("code", "SESSION_EVENTS_EXPIRED"));
              return;
            }
            if (path.contains("waitMs=10000")) {
              watching.countDown();
              try {
                release.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            }
            result =
                Map.of(
                    "events",
                    List.of(Map.of("sequence", 1, "type", "OLD_SESSION_EVENT")),
                    "nextSequence",
                    1,
                    "terminal",
                    true);
          } else result = snapshot(path.contains(SECOND) ? SECOND : JOB);
          respond(x, 200, result);
        });
    server.start();
  }

  @AfterEach
  void close() {
    release.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  SessionRepl repl(StringWriter out) {
    return new SessionRepl(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        new PrintWriter(out, true));
  }

  @Test
  void realConsoleReaderSendsOneTaskAndUsesSameHttpStatusPlanSkillsCancelCommands()
      throws Exception {
    var out = new StringWriter();
    try (var repl = repl(out)) {
      repl.run(new StringReader("Investigate code\n/status\n/plan\n/skills\n/cancel\n/quit\n"));
    }
    assertThat(models).hasValue(1);
    assertThat(requests).anyMatch(r -> r.contains("/commands") && r.contains("CANCEL"));
    assertThat(out.toString()).contains("review", "COMPLETED");
  }

  @Test
  void replayGapRecoversFromSnapshotWithGetOnly() throws Exception {
    var out = new StringWriter();
    expired.set(true);
    try (var repl = repl(out)) {
      repl.execute("/attach " + JOB);
      repl.execute("/events");
    }
    assertThat(models).hasValue(0);
    assertThat(out.toString()).contains("Старая лента недоступна");
    assertThat(requests).allMatch(r -> r.startsWith("GET"));
  }

  @Test
  void lateOldPollCannotPrintEventsOrChangeTheNewSessionCursor() throws Exception {
    var out = new StringWriter();
    try (var repl = repl(out)) {
      repl.execute("/attach " + JOB);
      repl.execute("/watch");
      assertThat(watching.await(2, TimeUnit.SECONDS)).isTrue();
      repl.execute("/attach " + SECOND);
      release.countDown();
      Thread.sleep(50);
      repl.execute("/status");
    }
    assertThat(out.toString()).contains(SECOND).doesNotContain("OLD_SESSION_EVENT");
    assertThat(models).hasValue(0);
  }

  static Map<String, Object> snapshot(String id) {
    return Map.of(
        "id",
        id,
        "conversationId",
        CONVERSATION,
        "status",
        "COMPLETED",
        "lastSequence",
        9,
        "plan",
        Map.of("steps", List.of()));
  }

  static void respond(HttpExchange x, int status, Object value) throws IOException {
    byte[] bytes = AgentJson.encode(value).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(status, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }
}
