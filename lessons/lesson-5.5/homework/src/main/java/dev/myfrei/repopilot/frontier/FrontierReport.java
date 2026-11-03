package dev.myfrei.repopilot.frontier;

import java.util.*;

public record FrontierReport(
    UUID runId,
    int revision,
    List<Check> checks,
    List<String> selectedStepIds,
    int usedLines,
    int remainingLines) {
  public FrontierReport {
    checks = List.copyOf(checks);
    selectedStepIds = List.copyOf(selectedStepIds);
  }

  public record Check(
      String stepId, String originalStatus, String status, int criticality, int lineCost) {}
}
