package dev.myfrei.repopilot.frontier;

import java.util.Objects;

/** Scheduling preview only: never starts steps or mutates a plan. */
public class PlanFrontierPolicy {
  public FrontierReport check(FrontierInput input) {
    Objects.requireNonNull(input);
    // TODO: classify prerequisites, rank eligible work and allocate bounded read lines.
    throw new UnsupportedOperationException("Implement PlanFrontierPolicy.check");
  }
}
