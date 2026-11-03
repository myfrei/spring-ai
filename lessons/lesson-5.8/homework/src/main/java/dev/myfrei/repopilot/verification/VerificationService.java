package dev.myfrei.repopilot.verification;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.workspace.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** Manual, exact-input, one-use permission. No automatic retries and no host build execution. */
public final class VerificationService {
  public static final String COMMAND = "STOCKROOM_CONTRACT_V1",
      SOURCE = "src/main/java/dev/myfrei/fixture/StockLedger.java";
  public static final int MAX_REQUESTS = 128, MAX_ATTEMPTS = 2;

  private record Stored(VerificationRequest view, byte[] bytes) {}

  private final WorkspaceService workspace;
  private final AgentStore store;
  private final VerificationRunner runner;
  private final Clock clock;
  private final String imageId;
  private final Map<UUID, Stored> requests = new LinkedHashMap<>();
  private final Map<UUID, VerificationReport> reports = new LinkedHashMap<>();
  private final Map<String, Integer> attempts = new HashMap<>();
  private boolean active;

  public VerificationService(
      WorkspaceService workspace,
      AgentStore store,
      VerificationRunner runner,
      String imageId,
      Clock clock) {
    this.workspace = Objects.requireNonNull(workspace);
    this.store = Objects.requireNonNull(store);
    this.runner = Objects.requireNonNull(runner);
    this.clock = Objects.requireNonNull(clock);
    if (imageId == null || !imageId.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException("Configure exact verifier image sha256 ID");
    this.imageId = imageId;
  }

  public synchronized VerificationRequest request(UUID conversation, String command) {
    store.conversation(conversation);
    if (!COMMAND.equals(command)) throw new VerificationProblem("VERIFICATION_COMMAND_DENIED");
    if (requests.size() >= MAX_REQUESTS) throw new VerificationProblem("VERIFICATION_CAPACITY");
    byte[] bytes = workspace.snapshotBytes(SOURCE);
    String revision = revision(bytes);
    checkAttempts(revision);
    var now = clock.instant();
    var view =
        new VerificationRequest(
            UUID.randomUUID(),
            conversation,
            COMMAND,
            revision,
            imageId,
            "PENDING",
            now,
            now.plusSeconds(300));
    requests.put(view.id(), new Stored(view, bytes.clone()));
    return view;
  }

  public synchronized VerificationRequest request(UUID id) {
    return required(id).view();
  }

  public synchronized VerificationReport report(UUID id) {
    var report = reports.get(id);
    if (report == null) throw new VerificationProblem("VERIFICATION_REPORT_NOT_FOUND");
    return report;
  }

  public synchronized List<VerificationReport> reports(UUID conversation) {
    store.conversation(conversation);
    return reports.values().stream().filter(r -> r.conversationId().equals(conversation)).toList();
  }

  public record VerificationState(String state, UUID reportId, String inputRevision) {}

  public synchronized VerificationState state(UUID conversation) {
    store.conversation(conversation);
    var history =
        reports.values().stream().filter(r -> r.conversationId().equals(conversation)).toList();
    String current = currentRevision();
    if (history.isEmpty()) return new VerificationState("UNVERIFIED", null, current);
    var latest = history.getLast();
    if (!latest.inputRevision().equals(current)
        || !latest.inputsStillCurrent()
        || !latest.imageId().equals(imageId)
        || !latest.command().equals(COMMAND))
      return new VerificationState("STALE", latest.id(), current);
    String state =
        latest.status().equals("PASS")
            ? "CHECKED_PASS"
            : latest.status().equals("FAIL") ? "CHECKED_FAIL" : "UNVERIFIED";
    return new VerificationState(state, latest.id(), current);
  }

  public VerificationRequest decide(UUID id, String decision) {
    Stored saved;
    synchronized (this) {
      if (!Set.of("APPROVE", "REJECT").contains(decision == null ? "" : decision))
        throw new VerificationProblem("VERIFICATION_INVALID_DECISION");
      saved = required(id);
      var v = saved.view();
      if (!v.status().equals("PENDING")) throw new VerificationProblem("VERIFICATION_NOT_PENDING");
      if (!clock.instant().isBefore(v.expiresAt())) {
        replace(saved, "EXPIRED");
        throw new VerificationProblem("VERIFICATION_EXPIRED");
      }
      if (decision.equals("REJECT")) return replace(saved, "REJECTED");
      if (active) throw new VerificationProblem("VERIFICATION_BUSY");
      if (!v.inputRevision().equals(currentRevision())) {
        replace(saved, "STALE");
        throw new VerificationProblem("VERIFICATION_STALE");
      }
      checkAttempts(v.inputRevision());
      attempts.merge(v.inputRevision(), 1, Integer::sum);
      replace(saved, "RUNNING");
      active = true;
    }
    var v = saved.view();
    Instant start = clock.instant();
    long nano = System.nanoTime();
    VerificationRunner.Result output;
    Path temp = null;
    try {
      temp = Files.createTempDirectory("repopilot-verification-");
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwxr-xr-x"));
      Path target = temp.resolve(SOURCE);
      Files.createDirectories(target.getParent());
      for (Path p = target.getParent(); p.startsWith(temp) && !p.equals(temp); p = p.getParent())
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
      Files.write(target, saved.bytes());
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"));
      output = Objects.requireNonNull(runner.run(imageId, temp));
    } catch (Exception failure) {
      output =
          new VerificationRunner.Result(
              null, "", "Verification infrastructure unavailable", false, false, "RUNNER_ERROR");
    } finally {
      if (temp != null)
        try (var files = Files.walk(temp)) {
          for (var p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (Exception ignored) {
          /* Container is already removed by its owner; no permission or result is created by cleanup. */
        }
    }
    synchronized (this) {
      try {
        boolean current = v.inputRevision().equals(currentRevision());
        String status =
            !current
                ? "STALE"
                : output.timedOut()
                    ? "TIMEOUT"
                    : output.reason().equals("CONTRACT_PASSED")
                            && Integer.valueOf(0).equals(output.exitCode())
                        ? "PASS"
                        : output.reason().equals("CONTRACT_FAILED")
                                && Integer.valueOf(10).equals(output.exitCode())
                            ? "FAIL"
                            : "ERROR";
        var report =
            new VerificationReport(
                UUID.randomUUID(),
                v.id(),
                v.conversationId(),
                v.command(),
                v.inputRevision(),
                imageId,
                status,
                output.exitCode(),
                prefix(output.stdout(), 8192),
                prefix(output.stderr(), 8192),
                output.outputTruncated(),
                current,
                start,
                clock.instant(),
                (System.nanoTime() - nano) / 1e6,
                output.reason());
        reports.put(report.id(), report);
        return replace(required(id), status);
      } finally {
        active = false;
      }
    }
  }

  private String currentRevision() {
    try {
      return revision(workspace.snapshotBytes(SOURCE));
    } catch (WorkspaceException unavailable) {
      return null;
    }
  }

  public static String revision(byte[] bytes) {
    return Hashes.sha256(
        AgentJson.canonical(
            Map.of(SOURCE, Hashes.sha256(new String(bytes, StandardCharsets.UTF_8)))));
  }

  private void checkAttempts(String revision) {
    if (attempts.getOrDefault(revision, 0) >= MAX_ATTEMPTS) {
      long failures =
          reports.values().stream()
              .filter(
                  r ->
                      r.inputRevision().equals(revision)
                          && r.imageId().equals(imageId)
                          && r.status().equals("FAIL"))
              .count();
      throw new VerificationProblem(
          failures >= 2 ? "VERIFICATION_NO_PROGRESS" : "VERIFICATION_ATTEMPT_LIMIT");
    }
  }

  private Stored required(UUID id) {
    var stored = requests.get(id);
    if (stored == null) throw new VerificationProblem("VERIFICATION_REQUEST_NOT_FOUND");
    return stored;
  }

  private VerificationRequest replace(Stored saved, String status) {
    var v = saved.view();
    var next =
        new VerificationRequest(
            v.id(),
            v.conversationId(),
            v.command(),
            v.inputRevision(),
            v.imageId(),
            status,
            v.createdAt(),
            v.expiresAt());
    requests.put(v.id(), new Stored(next, saved.bytes()));
    return next;
  }

  public List<AgentTool> reportTools() {
    return List.of(
        new AgentTool() {
          public String name() {
            return "readVerification";
          }

          public String description() {
            return "Read an actual fixed-contract verification report from this conversation. Data"
                + " only; does not run commands or grant permission. PASS refers only to"
                + " StockLedger contract.";
          }

          public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{\"reportId\":{\"type\":\"string\"}},\"required\":[\"reportId\"],\"additionalProperties\":false}";
          }

          public AgentToolResult execute(String raw, AgentToolContext context) {
            try {
              var a = AgentJson.arguments(raw, Set.of("reportId"), Set.of("reportId"));
              var result = report(UUID.fromString((String) a.get("reportId")));
              if (context == null || !result.conversationId().equals(context.conversationId()))
                throw new VerificationProblem("VERIFICATION_SCOPE_DENIED");
              var data = new LinkedHashMap<String, Object>();
              data.put("reportId", result.id());
              data.put("command", result.command());
              data.put("inputRevision", result.inputRevision());
              data.put("imageId", result.imageId());
              data.put("status", result.status());
              data.put("exitCode", result.exitCode());
              data.put(
                  "inputsStillCurrent",
                  result.inputsStillCurrent() && result.inputRevision().equals(currentRevision()));
              data.put("reason", result.reason());
              data.put("stdoutExcerpt", prefix(result.stdout(), 768));
              data.put("stderrExcerpt", prefix(result.stderr(), 256));
              data.put(
                  "outputTruncated",
                  result.outputTruncated()
                      || AgentJson.bytes(result.stdout()) > 768
                      || AgentJson.bytes(result.stderr()) > 256);
              String encoded = AgentJson.encode(data);
              if (AgentJson.bytes(AgentJson.encode(Map.of("ok", true, "code", "OK", "data", data)))
                  > 4096) throw new VerificationProblem("VERIFICATION_OUTPUT_LIMIT");
              return AgentToolResult.success(encoded);
            } catch (VerificationProblem e) {
              return AgentToolResult.error(
                  e.code(),
                  AgentJson.encode(
                      Map.of(
                          "code",
                          e.code(),
                          "message",
                          "Отчёт не получен; команда не запускалась.")));
            } catch (RuntimeException invalid) {
              return AgentToolResult.error(
                  "VERIFICATION_INVALID_REPORT_ID", "{\"message\":\"Проверьте reportId.\"}");
            }
          }
        });
  }

  private static String prefix(String text, int max) {
    if (text == null) return "";
    int end = 0, used = 0;
    while (end < text.length()) {
      int cp = text.codePointAt(end),
          size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
      if (used + size > max) break;
      used += size;
      end += Character.charCount(cp);
    }
    return text.substring(0, end);
  }
}
