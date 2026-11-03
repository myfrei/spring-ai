package dev.myfrei.repopilot.plan;

import java.util.*;

public record PlanSnapshot(UUID runId, int revision, List<StepView> steps) {
  public PlanSnapshot {
    steps = List.copyOf(steps);
  }

  public record StepView(
      String id,
      String title,
      String criterion,
      String path,
      int startLine,
      int endLine,
      List<String> dependsOn,
      boolean required,
      String status,
      String evidenceHash,
      boolean evidenceCurrent) {
    public StepView {
      dependsOn = List.copyOf(dependsOn);
    }
  }
}
