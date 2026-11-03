package dev.myfrei.repopilot.batch;

import java.util.*;

public record BatchReport(List<Check> checks, boolean ready) {
  public BatchReport {
    checks = List.copyOf(checks);
  }

  public record Check(UUID id, String path, String status) {}
}
