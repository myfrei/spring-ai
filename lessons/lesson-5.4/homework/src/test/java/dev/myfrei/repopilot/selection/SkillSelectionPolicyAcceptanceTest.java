package dev.myfrei.repopilot.selection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SkillSelectionPolicyAcceptanceTest {
  static final String V = "a".repeat(64), OLD = "b".repeat(64);
  final SkillSelectionPolicy policy = new SkillSelectionPolicy();

  static SelectionInput.Pin pin(String name) {
    return new SelectionInput.Pin(name, V);
  }

  static SelectionInput.Candidate c(String name, int bytes) {
    return new SelectionInput.Candidate(name, V, bytes);
  }

  SelectionReport check(
      List<SelectionInput.Pin> pins, List<SelectionInput.Candidate> catalog, int budget) {
    return policy.check(new SelectionInput(pins, catalog, budget));
  }

  static void statuses(SelectionReport report, String... codes) {
    assertEquals(
        List.of(codes), report.checks().stream().map(SelectionReport.Check::status).toList());
    assertEquals(Arrays.stream(codes).allMatch("SELECTED"::equals), report.allSelected());
  }

  @Test
  void selectsInCallerOrderAndAccountsExactBytes() {
    var pins = List.of(pin("b"), pin("a"));
    var out = check(pins, List.of(c("a", 300), c("b", 200)), 600);
    statuses(out, "SELECTED", "SELECTED");
    assertEquals(pins, out.selected());
    assertEquals(500, out.usedBytes());
    assertEquals(100, out.remainingBytes());
    assertEquals(
        List.of("b", "a"), out.checks().stream().map(SelectionReport.Check::name).toList());
  }

  @Test
  void firstOccurrenceOwnsTheNameEvenWhenItsVersionIsStale() {
    var out =
        check(
            List.of(new SelectionInput.Pin("a", OLD), pin("a"), pin("b"), pin("b")),
            List.of(c("a", 100), c("b", 200)),
            1000);
    statuses(out, "STALE", "DUPLICATE", "SELECTED", "DUPLICATE");
    assertEquals(List.of(pin("b")), out.selected());
    assertEquals(200, out.usedBytes());
  }

  @Test
  void unknownSkillIsReportedAndDoesNotConsumeBudget() {
    var out = check(List.of(pin("missing"), pin("a")), List.of(c("a", 300)), 300);
    statuses(out, "UNKNOWN", "SELECTED");
    assertEquals(300, out.usedBytes());
    assertEquals(0, out.remainingBytes());
  }

  @Test
  void stalePinIsNotSilentlyUpgraded() {
    var out =
        check(
            List.of(new SelectionInput.Pin("a", OLD), pin("b")),
            List.of(c("a", 100), c("b", 150)),
            200);
    statuses(out, "STALE", "SELECTED");
    assertEquals(OLD, out.checks().getFirst().version());
    assertEquals(List.of(pin("b")), out.selected());
  }

  @Test
  void atMostThreeAreSelected() {
    var pins = List.of(pin("a"), pin("b"), pin("c"), pin("d"));
    var out = check(pins, List.of(c("a", 1), c("b", 1), c("c", 1), c("d", 5000)), 100);
    statuses(out, "SELECTED", "SELECTED", "SELECTED", "SELECTION_LIMIT");
    assertEquals(3, out.usedBytes());
  }

  @Test
  void oversizedCandidateDoesNotBlockLaterSmallCandidate() {
    var out =
        check(
            List.of(pin("large"), pin("small"), pin("tiny")),
            List.of(c("large", 5000), c("small", 300), c("tiny", 100)),
            400);
    statuses(out, "OVER_BUDGET", "SELECTED", "SELECTED");
    assertEquals(List.of(pin("small"), pin("tiny")), out.selected());
    assertEquals(400, out.usedBytes());
  }

  @Test
  void exactBudgetFitsButOneByteMoreDoesNot() {
    var out = check(List.of(pin("a"), pin("b")), List.of(c("a", 4096), c("b", 1)), 4096);
    statuses(out, "SELECTED", "OVER_BUDGET");
    assertEquals(4096, out.usedBytes());
    assertEquals(0, out.remainingBytes());
  }

  @Test
  void priorityAndImmutableCopiesAreStable() {
    var requested =
        new ArrayList<>(
            List.of(
                pin("a"),
                pin("b"),
                pin("c"),
                pin("a"),
                pin("unknown"),
                new SelectionInput.Pin("d", OLD),
                pin("d")));
    var catalog = new ArrayList<>(List.of(c("a", 1), c("b", 1), c("c", 1), c("d", 5000)));
    var input = new SelectionInput(requested, catalog, 3);
    requested.clear();
    catalog.clear();
    var out = policy.check(input);
    statuses(out, "SELECTED", "SELECTED", "SELECTED", "DUPLICATE", "UNKNOWN", "STALE", "DUPLICATE");
    assertThrows(UnsupportedOperationException.class, () -> out.checks().clear());
    assertThrows(UnsupportedOperationException.class, () -> out.selected().clear());
    assertEquals(0, out.remainingBytes());
  }
}
