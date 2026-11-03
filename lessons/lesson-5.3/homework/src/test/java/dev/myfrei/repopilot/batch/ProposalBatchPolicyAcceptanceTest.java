package dev.myfrei.repopilot.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProposalBatchPolicyAcceptanceTest {
  static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  static final String H = "a".repeat(64), OTHER = "b".repeat(64);
  final ProposalBatchPolicy policy = new ProposalBatchPolicy();

  static BatchInput.Item item(
      UUID id, String path, String mode, String status, String current, Instant expires) {
    return new BatchInput.Item(id, path, mode, status, false, H, current, expires);
  }

  static BatchInput.Item ready(String path) {
    return item(UUID.randomUUID(), path, "EDIT", "PENDING", H, NOW.plusSeconds(60));
  }

  BatchReport check(BatchInput.Item... items) {
    return policy.check(new BatchInput(NOW, List.of(items)));
  }

  static void statuses(BatchReport report, String... statuses) {
    assertEquals(
        List.of(statuses), report.checks().stream().map(BatchReport.Check::status).toList());
    assertEquals(Arrays.stream(statuses).allMatch("READY"::equals), report.ready());
  }

  @Test
  void readyDistinctFilesPreservesOrderAndIds() {
    var a = ready("src/A.java");
    var b = ready("src/B.java");
    var out = check(a, b);
    statuses(out, "READY", "READY");
    assertEquals(
        List.of(a.id(), b.id()), out.checks().stream().map(BatchReport.Check::id).toList());
    assertEquals(
        List.of(a.path(), b.path()), out.checks().stream().map(BatchReport.Check::path).toList());
  }

  @Test
  void duplicatePathsAndDuplicateIdsMarkEveryConflictingEntry() {
    var a = ready("src/A.java");
    var b = ready("src/A.java");
    var c = item(a.id(), "src/C.java", "EDIT", "PENDING", H, NOW.plusSeconds(1));
    var d = ready("src/D.java");
    statuses(
        check(a, b, c, d), "CONFLICTING_PATH", "CONFLICTING_PATH", "CONFLICTING_PATH", "READY");
  }

  @Test
  void askAndPlanNeverBecomeReady() {
    statuses(
        check(
            item(UUID.randomUUID(), "src/A.java", "ASK", "PENDING", H, NOW.plusSeconds(1)),
            item(UUID.randomUUID(), "src/B.java", "PLAN", "PENDING", H, NOW.plusSeconds(1))),
        "MODE_DENIED",
        "MODE_DENIED");
  }

  @Test
  void everyTerminalOrPreviewStateIsNotPending() {
    String[] states = {"PREVIEW", "APPLIED", "REJECTED", "STALE", "EXPIRED", "FAILED"};
    var items = new ArrayList<BatchInput.Item>();
    for (int n = 0; n < states.length; n++)
      items.add(
          item(UUID.randomUUID(), "src/F" + n + ".java", "EDIT", states[n], H, NOW.plusSeconds(1)));
    var out = policy.check(new BatchInput(NOW, items));
    statuses(
        out,
        "NOT_PENDING",
        "NOT_PENDING",
        "NOT_PENDING",
        "NOT_PENDING",
        "NOT_PENDING",
        "NOT_PENDING");
  }

  @Test
  void expirationBoundaryIsInclusive() {
    statuses(
        check(
            item(UUID.randomUUID(), "src/A.java", "EDIT", "PENDING", H, NOW.minusSeconds(1)),
            item(UUID.randomUUID(), "src/B.java", "EDIT", "PENDING", H, NOW),
            item(UUID.randomUUID(), "src/C.java", "EDIT", "PENDING", H, NOW.plusNanos(1))),
        "EXPIRED",
        "EXPIRED",
        "READY");
  }

  @Test
  void missingCurrentHashDoesNotGuessSuccess() {
    statuses(
        check(
            item(UUID.randomUUID(), "src/A.java", "EDIT", "PENDING", null, NOW.plusSeconds(1)),
            ready("src/B.java")),
        "UNAVAILABLE",
        "READY");
  }

  @Test
  void changedVersionIsStale() {
    statuses(
        check(
            item(UUID.randomUUID(), "src/A.java", "EDIT", "PENDING", OTHER, NOW.plusSeconds(1)),
            ready("src/B.java")),
        "STALE",
        "READY");
  }

  @Test
  void priorityAndImmutabilityArePartOfContract() {
    var id = UUID.randomUUID();
    var a = item(id, "src/A.java", "ASK", "REJECTED", null, NOW);
    var b = item(UUID.randomUUID(), "src/A.java", "PLAN", "APPLIED", OTHER, NOW);
    var c = item(UUID.randomUUID(), "src/C.java", "PLAN", "REJECTED", null, NOW);
    var d = item(UUID.randomUUID(), "src/D.java", "EDIT", "REJECTED", null, NOW);
    var e = item(UUID.randomUUID(), "src/E.java", "EDIT", "PENDING", null, NOW);
    var active =
        new BatchInput.Item(
            UUID.randomUUID(), "src/Active.java", "EDIT", "PENDING", true, H, null, NOW);
    var out = check(a, b, c, d, e, active);
    statuses(
        out,
        "CONFLICTING_PATH",
        "CONFLICTING_PATH",
        "MODE_DENIED",
        "NOT_PENDING",
        "EXPIRED",
        "RUN_ACTIVE");
    assertThrows(UnsupportedOperationException.class, () -> out.checks().clear());
  }
}
