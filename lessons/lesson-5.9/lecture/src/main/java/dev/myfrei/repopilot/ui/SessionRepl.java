package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.agent.AgentJson;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** A real console HTTP client. No Spring context, model, tool dispatch or second agent loop. */
public final class SessionRepl implements AutoCloseable {
  private final HttpClient client;
  private final URI base;
  private final PrintWriter out;
  private UUID conversation;
  private volatile UUID session;
  private long cursor;
  private final java.util.concurrent.atomic.AtomicLong watchGeneration =
      new java.util.concurrent.atomic.AtomicLong();
  private Thread watcher;

  public SessionRepl(URI base, PrintWriter out) {
    if (!Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null)
      throw new IllegalArgumentException("HTTP server URL required");
    this.base = base;
    this.out = out;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  public static void main(String[] args) throws Exception {
    String url = args.length == 0 ? "http://127.0.0.1:8080" : args[0];
    try (var repl = new SessionRepl(URI.create(url), new PrintWriter(System.out, true))) {
      repl.run(new InputStreamReader(System.in));
    }
  }

  public void run(Reader input) throws IOException {
    out.println(
        "RepoPilot REPL: /help. Обычная строка отправляет задачу; /disconnect не отменяет её.");
    try (var lines = new BufferedReader(input)) {
      for (String line; (line = lines.readLine()) != null; ) {
        if (line.equals("/quit")) break;
        try {
          execute(line);
        } catch (Exception failure) {
          out.println("Ошибка: " + failure.getMessage());
        }
      }
    }
    disconnect();
  }

  public void execute(String line) throws Exception {
    String[] pair = line.strip().split("\\s+", 2);
    String command = pair[0], arg = pair.length > 1 ? pair[1] : "";
    switch (command) {
      case "" -> {}
      case "/help" ->
          out.println(
              "/status /plan /skills /cancel /events /watch /disconnect /attach ID /conversation ID"
                  + " /ask FILE /verify /show KIND ID /approve-patch ID /reject-patch ID"
                  + " /approve-check ID /reject-check ID /quit");
      case "/conversation" -> {
        disconnect();
        conversation = UUID.fromString(arg);
        session = null;
        cursor = 0;
        out.println("Диалог: " + conversation);
      }
      case "/attach" -> {
        disconnect();
        session = UUID.fromString(arg);
        cursor = 0;
        var value = get(snapshotPath());
        conversation = UUID.fromString((String) value.get("conversationId"));
        show(value);
      }
      case "/status" -> show(get(snapshotPath()));
      case "/plan" -> show(get(snapshotPath()).get("plan"));
      case "/skills" -> show(request("/api/sessions/skills", null));
      case "/cancel" -> mutate("CANCEL", null);
      case "/verify" -> mutate("REQUEST_VERIFICATION", null);
      case "/approve-patch" -> mutate("APPROVE_PATCH", UUID.fromString(arg));
      case "/reject-patch" -> mutate("REJECT_PATCH", UUID.fromString(arg));
      case "/approve-check" -> mutate("APPROVE_VERIFICATION", UUID.fromString(arg));
      case "/reject-check" -> mutate("REJECT_VERIFICATION", UUID.fromString(arg));
      case "/show" -> {
        var parts = arg.split("\\s+");
        if (parts.length != 2 || !Set.of("PATCH", "VERIFICATION").contains(parts[0]))
          throw new IllegalArgumentException("/show PATCH|VERIFICATION id");
        show(get(snapshotPath() + "/approvals/" + parts[0] + "/" + UUID.fromString(parts[1])));
      }
      case "/events" -> poll(0);
      case "/watch" -> {
        disconnect();
        watcher =
            Thread.startVirtualThread(
                () -> {
                  try {
                    while (!Thread.currentThread().isInterrupted()) {
                      if (poll(10000)) break;
                    }
                  } catch (Exception ended) {
                    out.println("Обновления отключены; задача не отменена.");
                  }
                });
      }
      case "/disconnect" -> {
        disconnect();
        out.println("Обновления отключены; задача не отменена.");
      }
      case "/ask" -> {
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of(arg))) {
          bytes = input.readNBytes(8193);
        }
        if (bytes.length > 8192)
          throw new IllegalArgumentException("Task JSON must not exceed8192bytes");
        String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Object value = AgentJson.parse(raw);
        if (!(value instanceof Map<?, ?>))
          throw new IllegalArgumentException("Research task JSON file required");
        submit(value);
      }
      default -> {
        if (command.startsWith("/"))
          throw new IllegalArgumentException("Неизвестная команда; /help");
        submit(defaultTask(line));
      }
    }
  }

  private Object defaultTask(String question) {
    return Map.of(
        "question",
        question,
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
                "Read StockLedger",
                "criterion",
                "READ_CODE",
                "path",
                "src/main/java/dev/myfrei/fixture/StockLedger.java",
                "startLine",
                17,
                "endLine",
                22,
                "dependsOn",
                List.of(),
                "required",
                true)));
  }

  private void submit(Object task) throws Exception {
    if (conversation == null)
      conversation = UUID.fromString((String) send("/api/agent/conversations", Map.of()).get("id"));
    UUID requestId = UUID.randomUUID();
    out.println("Идентификатор отправки: " + requestId);
    var result =
        send(
            "/api/sessions/conversations/" + conversation + "/jobs",
            Map.of("requestId", requestId, "task", task));
    disconnect();
    session = UUID.fromString((String) result.get("id"));
    cursor = 0;
    show(result);
  }

  private void mutate(String type, UUID approval) throws Exception {
    show(
        send(
            snapshotPath() + "/commands",
            approval == null
                ? Map.of("type", type)
                : Map.of("type", type, "approvalId", approval.toString())));
  }

  private String snapshotPath() {
    if (session == null) throw new IllegalStateException("Сначала отправьте задачу или /attach ID");
    return "/api/sessions/jobs/" + session;
  }

  private boolean poll(int waitMs) throws Exception {
    UUID target;
    long generation, after;
    synchronized (this) {
      if (session == null) throw new IllegalStateException("Select a session first");
      target = session;
      generation = watchGeneration.get();
      after = cursor;
    }
    Map<String, Object> page;
    try {
      page = get("/api/sessions/jobs/" + target + "/events?after=" + after + "&waitMs=" + waitMs);
    } catch (ApiFailure failure) {
      if (!"SESSION_EVENTS_EXPIRED".equals(failure.code)) throw failure;
      var snapshot = get("/api/sessions/jobs/" + target);
      synchronized (this) {
        if (generation != watchGeneration.get() || !target.equals(session)) return true;
        cursor = ((Number) snapshot.get("lastSequence")).longValue();
        out.println(
            "Старая лента недоступна; загружено актуальное состояние. Задача не запускалась"
                + " повторно.");
        show(snapshot);
        return Set.of("COMPLETED", "STOPPED", "FAILED").contains(snapshot.get("status"));
      }
    }
    synchronized (this) {
      if (generation != watchGeneration.get() || !target.equals(session)) return true;
      show(page.get("events"));
      cursor = ((Number) page.get("nextSequence")).longValue();
      return Boolean.TRUE.equals(page.get("terminal"));
    }
  }

  private static final class ApiFailure extends IOException {
    final String code;

    ApiFailure(int status, Object body) {
      super("HTTP " + status + ": " + AgentJson.encode(body));
      this.code =
          body instanceof Map<?, ?> map && map.get("code") instanceof String value ? value : null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String path) throws Exception {
    return (Map<String, Object>) request(path, null);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> send(String path, Object body) throws Exception {
    return (Map<String, Object>) request(path, body);
  }

  private Object request(String path, Object body) throws Exception {
    var builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(45))
            .header("Content-Type", "application/json");
    if (body == null) builder.GET();
    else builder.POST(HttpRequest.BodyPublishers.ofString(AgentJson.encode(body)));
    var result = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    Object value = AgentJson.parse(result.body());
    if (result.statusCode() >= 400) throw new ApiFailure(result.statusCode(), value);
    return value;
  }

  private void show(Object value) {
    out.println(AgentJson.encode(value));
    out.flush();
  }

  private void disconnect() {
    watchGeneration.incrementAndGet();
    var current = watcher;
    watcher = null;
    if (current != null) current.interrupt();
  }

  @Override
  public void close() {
    disconnect();
    client.close();
  }
}
