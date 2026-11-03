package dev.myfrei.repopilot.attention;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AttentionQueuePolicyAcceptanceTest {
  static final UUID CONVERSATION = UUID.randomUUID();
  final AttentionQueuePolicy policy = new AttentionQueuePolicy();

  static AttentionQueueInput.Approval a(String kind, UUID id, String status) {
    return new AttentionQueueInput.Approval(kind, id, status);
  }

  static AttentionQueueInput.Session s(
      UUID id, String status, long seq, long ack, AttentionQueueInput.Approval... approvals) {
    return new AttentionQueueInput.Session(
        id,
        status,
        status.equals("FAILED") ? "ENGINE_ERROR" : status.equals("STOPPED") ? "CONTEXT_LIMIT" : "",
        seq,
        ack,
        List.of(approvals));
  }

  AttentionQueueReport check(int max, AttentionQueueInput.Session... sessions) {
    return policy.check(new AttentionQueueInput(CONVERSATION, List.of(sessions), max));
  }

  @Test
  void pendingApprovalsPrecedeFailuresAndStoppedSessions() {
    var stopped = UUID.randomUUID();
    var failed = UUID.randomUUID();
    var waiting = UUID.randomUUID();
    var approval = UUID.randomUUID();
    var out =
        check(
            12,
            s(stopped, "STOPPED", 999, -1),
            s(failed, "FAILED", 2, -1),
            s(waiting, "COMPLETED", 1, -1, a("PATCH", approval, "PENDING")));
    assertEquals(
        List.of("APPROVAL_REQUIRED", "FAILED", "STOPPED"),
        out.notices().stream().map(AttentionQueueReport.Notice::kind).toList());
    assertEquals(approval, out.notices().getFirst().approvalId());
    assertEquals(3, out.eligibleCount());
    assertEquals(1, out.blockingCount());
    assertEquals(CONVERSATION, out.conversationId());
  }

  @Test
  void acknowledgingASequenceCannotHidePendingApproval() {
    var id = UUID.randomUUID();
    var approval = UUID.randomUUID();
    var out =
        check(
            12,
            s(id, "FAILED", 7, 7, a("VERIFICATION", approval, "PENDING")),
            s(UUID.randomUUID(), "STOPPED", 5, 5));
    assertEquals(1, out.notices().size());
    assertEquals("APPROVAL_REQUIRED", out.notices().getFirst().kind());
    assertTrue(out.notices().getFirst().blocking());
    assertEquals(7, out.notices().getFirst().sequence());
  }

  @Test
  void sharedApprovalsAreDeduplicatedByKindAndIdButPreviewsNeverConsumeTheKey() {
    var shared = UUID.randomUUID();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var out =
        check(
            12,
            s(
                first,
                "COMPLETED",
                1,
                -1,
                a("VERIFICATION", shared, "PREVIEW"),
                a("PATCH", shared, "PENDING")),
            s(
                second,
                "RUNNING",
                500,
                -1,
                a("VERIFICATION", shared, "PENDING"),
                a("PATCH", shared, "PENDING")));
    assertEquals(2, out.notices().size());
    assertEquals(
        List.of("PATCH", "VERIFICATION"),
        out.notices().stream().map(AttentionQueueReport.Notice::approvalKind).toList());
    assertEquals(first, out.notices().getFirst().sessionId());
    assertEquals(second, out.notices().getLast().sessionId());
  }

  @Test
  void newestDuplicateSnapshotWinsWithoutMovingTheFirstSessionPosition() {
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    var old = UUID.randomUUID();
    var out =
        check(
            12,
            s(a, "STOPPED", 1, -1, a("PATCH", old, "PENDING")),
            s(b, "STOPPED", 100, -1),
            s(a, "STOPPED", 5, -1));
    assertEquals(
        List.of(a, b), out.notices().stream().map(AttentionQueueReport.Notice::sessionId).toList());
    assertEquals(5, out.notices().getFirst().sequence());
    assertEquals(0, out.blockingCount());
    assertEquals(5, out.highWatermarks().get(a));
  }

  @Test
  void aLimitNeverSilentlyLosesBlockingWork() {
    var id = UUID.randomUUID();
    var session =
        s(
            id,
            "FAILED",
            3,
            -1,
            a("PATCH", UUID.randomUUID(), "PENDING"),
            a("VERIFICATION", UUID.randomUUID(), "PENDING"));
    var limited = check(1, session);
    assertEquals(3, limited.eligibleCount());
    assertEquals(2, limited.blockingCount());
    assertEquals(2, limited.omittedCount());
    assertEquals(1, limited.omittedBlockingCount());
    assertFalse(limited.allActionableVisible());
    var both = check(2, session);
    assertEquals(1, both.omittedCount());
    assertEquals(0, both.omittedBlockingCount());
    assertTrue(both.allActionableVisible());
  }

  @Test
  void ordinaryRunningQueuedCompletedAndPreviewOnlySessionsNeedNoAttention() {
    var out =
        check(
            12,
            s(UUID.randomUUID(), "QUEUED", 0, -1),
            s(UUID.randomUUID(), "RUNNING", 3, -1),
            s(UUID.randomUUID(), "COMPLETED", 5, -1, a("PATCH", UUID.randomUUID(), "PREVIEW")));
    assertTrue(out.notices().isEmpty());
    assertEquals(0, out.eligibleCount());
    assertTrue(out.allActionableVisible());
    assertEquals(3, out.highWatermarks().size());
  }

  @Test
  void unrelatedSessionSequencesNeverChangeTieOrderAndOnlyAcknowledgedEventsDisappear() {
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var out = check(12, s(first, "STOPPED", 2, 1), s(second, "STOPPED", 999, -1));
    assertEquals(
        List.of(first, second),
        out.notices().stream().map(AttentionQueueReport.Notice::sessionId).toList());
    assertEquals(
        List.of("session:" + first + ":terminal", "session:" + second + ":terminal"),
        out.notices().stream().map(AttentionQueueReport.Notice::key).toList());
  }

  @Test
  void inputAndOutputCollectionsAreImmutableAndSameSequenceUsesTheLastSnapshot() {
    var id = UUID.randomUUID();
    var source = new ArrayList<>(List.of(s(id, "STOPPED", 2, -1), s(id, "FAILED", 2, -1)));
    var input = new AttentionQueueInput(CONVERSATION, source, 12);
    source.clear();
    var out = policy.check(input);
    assertEquals("FAILED", out.notices().getFirst().kind());
    assertThrows(UnsupportedOperationException.class, () -> out.notices().clear());
    assertThrows(UnsupportedOperationException.class, () -> out.highWatermarks().clear());
  }
}
