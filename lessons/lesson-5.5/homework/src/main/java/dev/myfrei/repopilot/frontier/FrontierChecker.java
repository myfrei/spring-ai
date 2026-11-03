package dev.myfrei.repopilot.frontier;

import dev.myfrei.repopilot.plan.*;
import java.util.*;
import java.util.function.Function;

/** The caller cannot supply statuses, dependencies or evidence. */
public class FrontierChecker {
  private final Function<UUID, PlanSnapshot> snapshot;
  private final PlanFrontierPolicy policy;

  public FrontierChecker(PlanRuntime runtime, PlanFrontierPolicy policy) {
    this(runtime::snapshot, policy);
  }

  FrontierChecker(Function<UUID, PlanSnapshot> snapshot, PlanFrontierPolicy policy) {
    this.snapshot = Objects.requireNonNull(snapshot);
    this.policy = Objects.requireNonNull(policy);
  }

  public FrontierReport check(UUID runId, FrontierRequest request) {
    var plan = snapshot.apply(runId);
    if (!runId.equals(plan.runId())) throw new IllegalArgumentException("Plan scope mismatch");
    var steps =
        plan.steps().stream()
            .map(
                s ->
                    new FrontierInput.Step(
                        s.id(),
                        s.status(),
                        s.evidenceCurrent(),
                        s.startLine(),
                        s.endLine(),
                        s.dependsOn(),
                        s.required()))
            .toList();
    return policy.check(
        new FrontierInput(
            plan.runId(), plan.revision(), steps, request.maxSteps(), request.readLineBudget()));
  }
}
