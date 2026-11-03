package dev.myfrei.repopilot.research;

import java.util.*;

public record ResearchSnapshot(
    UUID parentRunId,
    String status,
    boolean cancelled,
    ResearchBudgetSnapshot budget,
    List<ResearchChildReport> children) {
  public ResearchSnapshot {
    children = List.copyOf(children);
  }
}
