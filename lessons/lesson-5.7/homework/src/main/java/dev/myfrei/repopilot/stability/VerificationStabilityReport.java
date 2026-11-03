package dev.myfrei.repopilot.stability;

import java.util.*;

public record VerificationStabilityReport(
    UUID conversationId, List<SampleCheck> checks, List<Group> groups) {
  public VerificationStabilityReport {
    checks = List.copyOf(checks);
    groups = List.copyOf(groups);
  }

  public record SampleCheck(UUID reportId, String status) {}

  public record Group(
      String command,
      String inputRevision,
      String imageId,
      String status,
      int sampleCount,
      int passCount,
      int failCount,
      int inconclusiveCount,
      int truncatedOutputs) {}
}
