package dev.myfrei.repopilot.researchplan;

import java.util.Objects;

/** Read-only plan preview: creates neither executions nor read receipts. */
public class ResearchCheckPlanPolicy {
  public ResearchCheckPlanReport check(ResearchCheckPlanInput input) {
    Objects.requireNonNull(input);
    // TODO: reject stale versions and plan the uncovered line ranges within the read budget.
    throw new UnsupportedOperationException("Implement ResearchCheckPlanPolicy.check");
  }
}
