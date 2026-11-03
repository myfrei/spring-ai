package dev.myfrei.repopilot.verification;

import dev.myfrei.repopilot.agent.AgentJson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Host launches only fixed Docker commands. Input POM, plugins and scripts are never executed. */
public final class DockerVerificationRunner implements VerificationRunner {
  public record Output(
      Integer exitCode, String stdout, String stderr, boolean truncated, boolean timedOut) {}

  @FunctionalInterface
  public interface Executor {
    Output execute(List<String> argv, Duration timeout, int bound);
  }

  private final Executor executor;
  private final Duration timeout;

  public DockerVerificationRunner() {
    this(new BoundedCommand(), Duration.ofSeconds(30));
  }

  public DockerVerificationRunner(Executor executor, Duration timeout) {
    this.executor = Objects.requireNonNull(executor);
    this.timeout = Objects.requireNonNull(timeout);
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalArgumentException("Timeout1..60seconds");
  }

  public static List<String> createCommand(String imageId, Path snapshot, String name) {
    if (imageId == null
        || !imageId.matches("sha256:[0-9a-f]{64}")
        || name == null
        || !name.matches("repopilot-check-[0-9a-f-]{36}")
        || snapshot == null)
      throw new IllegalArgumentException("Exact image and owned container required");
    String source = snapshot.toAbsolutePath().normalize().toString();
    if (source.contains(",") || source.contains("\n"))
      throw new IllegalArgumentException("Unsupported snapshot path");
    return List.of(
        "docker",
        "create",
        "--pull=never",
        "--name",
        name,
        "--label",
        "repopilot.verification=owned",
        "--network",
        "none",
        "--user",
        "65532:65532",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges=true",
        "--memory",
        "512m",
        "--memory-swap",
        "512m",
        "--cpus",
        "1",
        "--pids-limit",
        "64",
        "--ulimit",
        "nofile=128:128",
        "--tmpfs",
        "/build:rw,noexec,nosuid,size=67108864,mode=1777",
        "--mount",
        "type=bind,src=" + source + ",dst=/input,readonly",
        imageId);
  }

  @Override
  public Result run(String imageId, Path snapshot) {
    String name = "repopilot-check-" + UUID.randomUUID();
    var create = createCommand(imageId, snapshot, name);
    Result result = null;
    boolean attempted = false;
    try {
      attempted = true;
      var made = executor.execute(create, Duration.ofSeconds(10), 1024);
      if (made.timedOut() || !Integer.valueOf(0).equals(made.exitCode()))
        result =
            new Result(
                null,
                "",
                made.stderr(),
                made.truncated(),
                made.timedOut(),
                "CONTAINER_CREATE_FAILED");
      else {
        var started = executor.execute(List.of("docker", "start", "--attach", name), timeout, 8192);
        if (started.timedOut())
          result =
              new Result(
                  null,
                  started.stdout(),
                  started.stderr(),
                  started.truncated(),
                  true,
                  "RUN_TIMEOUT");
        else {
          var inspected =
              executor.execute(
                  List.of("docker", "inspect", "--format", "{{json .State}}", name),
                  Duration.ofSeconds(5),
                  2048);
          try {
            if (!Integer.valueOf(0).equals(inspected.exitCode()) || inspected.timedOut())
              throw new IllegalStateException();
            Object parsed = AgentJson.parse(inspected.stdout());
            if (!(parsed instanceof Map<?, ?> state)
                || !Boolean.FALSE.equals(state.get("Running"))
                || !(state.get("ExitCode") instanceof Integer exit))
              throw new IllegalStateException();
            String reason =
                Boolean.TRUE.equals(state.get("OOMKilled"))
                    ? "MEMORY_LIMIT"
                    : exit == 0
                        ? "CONTRACT_PASSED"
                        : exit == 10
                            ? "CONTRACT_FAILED"
                            : exit == 124 ? "RUN_TIMEOUT" : "HARNESS_ERROR";
            result =
                new Result(
                    exit,
                    started.stdout(),
                    started.stderr(),
                    started.truncated(),
                    exit == 124,
                    reason);
          } catch (RuntimeException invalid) {
            result =
                new Result(
                    null,
                    started.stdout(),
                    started.stderr(),
                    started.truncated(),
                    false,
                    "CONTAINER_STATE_UNKNOWN");
          }
        }
      }
    } catch (RuntimeException failure) {
      result = new Result(null, "", "Runner infrastructure error", false, false, "RUNNER_ERROR");
    } finally {
      if (attempted) {
        boolean interrupted = Thread.interrupted();
        try {
          var removed =
              executor.execute(
                  List.of("docker", "rm", "--force", name), Duration.ofSeconds(5), 1024);
          if (removed.timedOut() || !Integer.valueOf(0).equals(removed.exitCode()))
            result =
                new Result(
                    null,
                    result == null ? "" : result.stdout(),
                    "Container cleanup failed",
                    result != null && result.outputTruncated(),
                    false,
                    "CLEANUP_FAILED");
        } finally {
          if (interrupted) Thread.currentThread().interrupt();
        }
      }
    }
    return Objects.requireNonNull(result);
  }
}
