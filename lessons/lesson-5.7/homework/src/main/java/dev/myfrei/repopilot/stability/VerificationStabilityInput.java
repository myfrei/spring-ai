package dev.myfrei.repopilot.stability;

import java.util.*;

/** Historical metadata from server reports. Contains no output logs. */
public record VerificationStabilityInput(UUID conversationId, List<Sample> samples) {
  public VerificationStabilityInput {
    Objects.requireNonNull(conversationId);
    if (samples == null
        || samples.isEmpty()
        || samples.size() > 8
        || samples.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Expected 1–8 server reports");
    samples = List.copyOf(samples);
  }

  public record Sample(
      UUID id,
      UUID conversationId,
      String command,
      String inputRevision,
      String imageId,
      String status,
      Integer exitCode,
      boolean inputsStillCurrent,
      boolean outputTruncated) {
    public Sample {
      Objects.requireNonNull(id);
      Objects.requireNonNull(conversationId);
      if (command == null
          || command.isBlank()
          || command.length() > 128
          || inputRevision == null
          || !inputRevision.matches("[a-f0-9]{64}")
          || imageId == null
          || imageId.isBlank()
          || imageId.length() > 256
          || status == null
          || status.isBlank()
          || status.length() > 64) throw new IllegalArgumentException("Invalid report metadata");
    }
  }
}
