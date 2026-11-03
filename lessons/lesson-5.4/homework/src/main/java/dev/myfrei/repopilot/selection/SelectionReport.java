package dev.myfrei.repopilot.selection;

import java.util.*;

public record SelectionReport(
    List<Check> checks,
    List<SelectionInput.Pin> selected,
    int usedBytes,
    int remainingBytes,
    boolean allSelected) {
  public SelectionReport {
    checks = List.copyOf(checks);
    selected = List.copyOf(selected);
  }

  public record Check(String name, String version, String status) {}
}
