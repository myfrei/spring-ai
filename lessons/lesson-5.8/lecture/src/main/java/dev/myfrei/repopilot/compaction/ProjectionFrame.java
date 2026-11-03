package dev.myfrei.repopilot.compaction;

import java.util.List;

/**
 * Local estimates and bounded metadata for one prompt-admission attempt. No provider usage
 * invented.
 */
public record ProjectionFrame(
    int sequence,
    int fullEstimatedTokens,
    int projectedEstimatedTokens,
    int actualInputBudget,
    int requestedInputBudget,
    int effectiveInputBudget,
    int compactedExchanges,
    int pinnedMessages,
    int omittedToolBytes,
    int summaryBytes,
    boolean admitted,
    List<String> omittedCurrentCallIds) {
  public ProjectionFrame {
    omittedCurrentCallIds = List.copyOf(omittedCurrentCallIds);
  }
}
