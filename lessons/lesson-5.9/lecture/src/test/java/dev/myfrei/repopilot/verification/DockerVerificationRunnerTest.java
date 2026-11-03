package dev.myfrei.repopilot.verification;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class DockerVerificationRunnerTest {
  @Test
  void fixedCommandPinsImageDisablesNetworkAndHostWriteAndCapsResources() {
    var command =
        DockerVerificationRunner.createCommand(
            "sha256:" + "a".repeat(64),
            Path.of("/tmp/snapshot"),
            "repopilot-check-" + UUID.randomUUID());
    assertThat(command)
        .containsSubsequence(
            "--network", "none", "--user", "65532:65532", "--read-only", "--cap-drop", "ALL");
    assertThat(command)
        .contains(
            "no-new-privileges=true",
            "512m",
            "64",
            "1",
            "/build:rw,noexec,nosuid,size=67108864,mode=1777",
            "type=bind,src=/tmp/snapshot,dst=/input,readonly");
    assertThat(command)
        .doesNotContain(
            "sh", "bash", "mvn", "/var/run/docker.sock", "--privileged", "--network=host");
    assertThat(command.getLast()).isEqualTo("sha256:" + "a".repeat(64));
  }

  @Test
  void observedContainerStateNotDockerClientExitDeterminesOutcomeAndCleanupAlwaysRuns() {
    var commands = new ArrayList<List<String>>();
    var runner =
        new DockerVerificationRunner(
            (argv, t, b) -> {
              commands.add(argv);
              if (argv.contains("inspect"))
                return new DockerVerificationRunner.Output(
                    0, "{\"Running\":false,\"ExitCode\":10,\"OOMKilled\":false}", "", false, false);
              return new DockerVerificationRunner.Output(0, "trusted output", "", false, false);
            },
            Duration.ofSeconds(5));
    var result = runner.run("sha256:" + "a".repeat(64), Path.of("/tmp/input"));
    assertThat(result.exitCode()).isEqualTo(10);
    assertThat(result.reason()).isEqualTo("CONTRACT_FAILED");
    assertThat(commands.getLast()).containsSubsequence("docker", "rm", "--force");
  }

  @Test
  void timeoutAndUnknownStateNeverBecomePassingAndOwnedContainerIsRemoved() {
    for (boolean timeout : List.of(false, true)) {
      var commands = new ArrayList<List<String>>();
      var runner =
          new DockerVerificationRunner(
              (argv, t, b) -> {
                commands.add(argv);
                if (argv.contains("start"))
                  return new DockerVerificationRunner.Output(null, "", "", false, timeout);
                if (argv.contains("inspect"))
                  return new DockerVerificationRunner.Output(0, "bad", "", false, false);
                return new DockerVerificationRunner.Output(0, "id", "", false, false);
              },
              Duration.ofSeconds(1));
      var result = runner.run("sha256:" + "a".repeat(64), Path.of("/tmp/input"));
      assertThat(result.reason()).isEqualTo(timeout ? "RUN_TIMEOUT" : "CONTAINER_STATE_UNKNOWN");
      assertThat(result.exitCode()).isNull();
      assertThat(commands.getLast()).contains("rm", "--force");
    }
  }

  @Test
  void mutableImagesAndUnrepresentableBindPathsAreRejectedBeforeDocker() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                DockerVerificationRunner.createCommand(
                    "image:latest", Path.of("/tmp/input"), "repopilot-check-" + UUID.randomUUID()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                DockerVerificationRunner.createCommand(
                    "sha256:" + "a".repeat(64),
                    Path.of("/tmp/with,comma"),
                    "repopilot-check-" + UUID.randomUUID()));
  }
}
