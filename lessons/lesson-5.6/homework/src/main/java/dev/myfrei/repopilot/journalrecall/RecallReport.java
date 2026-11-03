package dev.myfrei.repopilot.journalrecall;

import java.util.*;

public record RecallReport(
    UUID runId, List<Check> checks, int usedBytes, int remainingBytes, boolean allRecalled) {
  public RecallReport {
    checks = List.copyOf(checks);
  }

  public record Check(String callId, String status, String toolCode, String resultJson) {}
}
