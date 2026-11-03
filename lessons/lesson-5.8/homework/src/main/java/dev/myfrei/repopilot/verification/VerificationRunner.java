package dev.myfrei.repopilot.verification;

import java.nio.file.Path;

@FunctionalInterface
public interface VerificationRunner {
  record Result(
      Integer exitCode,
      String stdout,
      String stderr,
      boolean outputTruncated,
      boolean timedOut,
      String reason) {}

  Result run(String imageId, Path snapshot);
}
