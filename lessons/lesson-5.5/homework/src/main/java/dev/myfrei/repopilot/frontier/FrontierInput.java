package dev.myfrei.repopilot.frontier;

import java.util.*;

/** Server-owned fresh plan facts; request controls only scheduling preview limits. */
public record FrontierInput(
    UUID runId, int revision, List<Step> steps, int maxSteps, int readLineBudget) {
  public FrontierInput {
    Objects.requireNonNull(runId);
    if (revision < 0
        || steps == null
        || steps.isEmpty()
        || steps.size() > 4
        || steps.stream().anyMatch(Objects::isNull)
        || maxSteps < 1
        || maxSteps > 4
        || readLineBudget < 1
        || readLineBudget > 160) throw new IllegalArgumentException("Invalid frontier bounds");
    steps = List.copyOf(steps);
    var seen = new HashSet<String>();
    for (var step : steps) {
      if (!seen.containsAll(step.dependsOn()) || !seen.add(step.id()))
        throw new IllegalArgumentException("Expected unique steps with earlier dependencies");
    }
  }

  public record Step(
      String id,
      String status,
      boolean evidenceCurrent,
      int startLine,
      int endLine,
      List<String> dependsOn,
      boolean required) {
    public Step {
      if (id == null
          || !id.matches("[a-z][a-z0-9-]{0,15}")
          || status == null
          || !Set.of("PENDING", "ACTIVE", "COMPLETED").contains(status)
          || startLine < 1
          || endLine < startLine
          || (long) endLine - startLine + 1 > 80
          || dependsOn == null
          || dependsOn.size() > 3
          || dependsOn.stream().anyMatch(Objects::isNull)
          || new HashSet<>(dependsOn).size() != dependsOn.size())
        throw new IllegalArgumentException("Invalid frontier step");
      dependsOn = List.copyOf(dependsOn);
    }
  }
}
