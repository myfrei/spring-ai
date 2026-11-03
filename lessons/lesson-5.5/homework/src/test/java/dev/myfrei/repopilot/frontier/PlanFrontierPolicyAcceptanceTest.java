package dev.myfrei.repopilot.frontier;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PlanFrontierPolicyAcceptanceTest {
  static final UUID RUN = UUID.randomUUID();
  final PlanFrontierPolicy policy = new PlanFrontierPolicy();

  static FrontierInput.Step s(
      String id, String status, boolean current, int lines, boolean required, String... deps) {
    return new FrontierInput.Step(id, status, current, 10, 9 + lines, List.of(deps), required);
  }

  FrontierReport check(int max, int budget, FrontierInput.Step... steps) {
    return policy.check(new FrontierInput(RUN, 7, List.of(steps), max, budget));
  }

  static void statuses(FrontierReport out, String... statuses) {
    assertEquals(
        List.of(statuses), out.checks().stream().map(FrontierReport.Check::status).toList());
  }

  @Test
  void independentTiesUsePlanOrderRatherThanOwnRequiredFlag() {
    var out = check(4, 10, s("a", "PENDING", false, 2, false), s("b", "PENDING", false, 3, true));
    assertEquals(RUN, out.runId());
    assertEquals(7, out.revision());
    assertEquals(List.of("a", "b"), out.selectedStepIds());
    statuses(out, "SELECTED", "SELECTED");
    assertEquals(5, out.usedLines());
    assertEquals(5, out.remainingLines());
    assertEquals(
        List.of(0, 0), out.checks().stream().map(FrontierReport.Check::criticality).toList());
  }

  @Test
  void activePrecedesPendingEvenWhenPendingUnblocksMoreRequiredWork() {
    var out =
        check(
            2,
            20,
            s("a", "PENDING", false, 1, false),
            s("b", "ACTIVE", false, 1, false),
            s("c", "PENDING", false, 1, true, "a"),
            s("d", "PENDING", false, 1, true, "c"));
    assertEquals(List.of("b", "a"), out.selectedStepIds());
    statuses(out, "SELECTED", "SELECTED", "BLOCKED_DEPENDENCY", "BLOCKED_DEPENDENCY");
    assertEquals(2, out.checks().getFirst().criticality());
  }

  @Test
  void transitiveRequiredDescendantsOutrankAnEarlierIndependentStep() {
    var out =
        check(
            1,
            10,
            s("early", "PENDING", false, 1, true),
            s("root", "PENDING", false, 1, false),
            s("middle", "PENDING", false, 1, false, "root"),
            s("required", "PENDING", false, 1, true, "middle"));
    assertEquals(List.of("root"), out.selectedStepIds());
    statuses(out, "SELECTION_LIMIT", "SELECTED", "BLOCKED_DEPENDENCY", "BLOCKED_DEPENDENCY");
    assertEquals(1, out.checks().get(1).criticality());
    assertEquals(1, out.checks().get(2).criticality());
  }

  @Test
  void sharedRequiredDescendantIsCountedOnceInADiamond() {
    var out =
        check(
            2,
            10,
            s("root", "COMPLETED", true, 1, false),
            s("left", "PENDING", false, 1, false, "root"),
            s("right", "PENDING", false, 1, false, "root"),
            s("end", "PENDING", false, 1, true, "left", "right"));
    assertEquals(List.of("left", "right"), out.selectedStepIds());
    assertEquals(1, out.checks().getFirst().criticality());
    assertEquals(
        List.of(1, 1, 1, 0), out.checks().stream().map(FrontierReport.Check::criticality).toList());
  }

  @Test
  void aStaleTransitiveAncestorBlocksActiveWorkAndCompletedStepsAreNotRestarted() {
    var out =
        check(
            4,
            10,
            s("a", "COMPLETED", false, 1, true),
            s("b", "COMPLETED", true, 1, true, "a"),
            s("c", "ACTIVE", false, 1, true, "b"),
            s("d", "COMPLETED", true, 1, true));
    statuses(out, "STALE_COMPLETED", "COMPLETED", "BLOCKED_DEPENDENCY", "COMPLETED");
    assertTrue(out.selectedStepIds().isEmpty());
    assertEquals(0, out.usedLines());
  }

  @Test
  void oversizedWorkIsSkippedAndLaterSmallWorkFitsTheExactBudget() {
    var out =
        check(
            4,
            3,
            s("large", "ACTIVE", false, 80, true),
            s("one", "PENDING", false, 1, true),
            s("two", "PENDING", false, 2, true),
            s("late", "PENDING", false, 1, true));
    statuses(out, "OVER_BUDGET", "SELECTED", "SELECTED", "OVER_BUDGET");
    assertEquals(List.of("one", "two"), out.selectedStepIds());
    assertEquals(3, out.usedLines());
    assertEquals(0, out.remainingLines());
    assertEquals(80, out.checks().getFirst().lineCost());
  }

  @Test
  void selectedCountLimitHasPriorityOverAnInsufficientRemainingBudget() {
    var out =
        check(1, 1, s("active", "ACTIVE", false, 1, true), s("other", "PENDING", false, 80, true));
    statuses(out, "SELECTED", "SELECTION_LIMIT");
    assertEquals(List.of("active"), out.selectedStepIds());
  }

  @Test
  void immutableCopiesKeepCheckOrderSeparateFromSelectionOrder() {
    var source =
        new ArrayList<>(
            List.of(s("first", "PENDING", false, 1, true), s("second", "ACTIVE", false, 1, true)));
    var input = new FrontierInput(RUN, 1, source, 2, 2);
    source.clear();
    var out = policy.check(input);
    assertEquals(
        List.of("first", "second"),
        out.checks().stream().map(FrontierReport.Check::stepId).toList());
    assertEquals(
        List.of("PENDING", "ACTIVE"),
        out.checks().stream().map(FrontierReport.Check::originalStatus).toList());
    assertEquals(List.of("second", "first"), out.selectedStepIds());
    assertThrows(UnsupportedOperationException.class, () -> out.checks().clear());
    assertThrows(UnsupportedOperationException.class, () -> out.selectedStepIds().clear());
  }
}
