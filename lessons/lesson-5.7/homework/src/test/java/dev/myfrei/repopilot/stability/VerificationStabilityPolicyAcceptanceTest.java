package dev.myfrei.repopilot.stability;

import static org.junit.jupiter.api.Assertions.*;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class VerificationStabilityPolicyAcceptanceTest {
  static final UUID CONVERSATION = UUID.randomUUID();
  static final String REV = "a".repeat(64),
      IMAGE = "sha256:" + "b".repeat(64),
      COMMAND = "STOCKROOM_CONTRACT_V1";
  final VerificationStabilityPolicy policy = new VerificationStabilityPolicy();

  static VerificationStabilityInput.Sample s(
      String status, Integer exit, boolean current, boolean truncated) {
    return new VerificationStabilityInput.Sample(
        UUID.randomUUID(), CONVERSATION, COMMAND, REV, IMAGE, status, exit, current, truncated);
  }

  VerificationStabilityReport check(VerificationStabilityInput.Sample... samples) {
    return policy.check(new VerificationStabilityInput(CONVERSATION, List.of(samples)));
  }

  @Test
  void twoIndependentPassReportsAreConsistentObservations() {
    var out = check(s("PASS", 0, true, false), s("PASS", 0, true, false));
    assertEquals(CONVERSATION, out.conversationId());
    assertEquals(1, out.groups().size());
    var g = out.groups().getFirst();
    assertEquals("CONSISTENT_PASS", g.status());
    assertEquals(2, g.sampleCount());
    assertEquals(2, g.passCount());
    assertEquals(0, g.failCount());
    assertEquals(0, g.inconclusiveCount());
    assertEquals(COMMAND, g.command());
    assertEquals(REV, g.inputRevision());
    assertEquals(IMAGE, g.imageId());
  }

  @Test
  void nonzeroFailuresRemainFailuresWithoutPretendingTheyAreIdenticalTestErrors() {
    var out = check(s("FAIL", 1, true, false), s("FAIL", 2, true, false));
    var g = out.groups().getFirst();
    assertEquals("CONSISTENT_FAIL", g.status());
    assertEquals(2, g.failCount());
    assertEquals(
        List.of("CONCLUSIVE_FAIL", "CONCLUSIVE_FAIL"),
        out.checks().stream().map(VerificationStabilityReport.SampleCheck::status).toList());
  }

  @Test
  void opposingConclusiveResultsTakePriorityOverAnInconclusiveAttempt() {
    var out =
        check(s("PASS", 0, true, false), s("FAIL", 1, true, false), s("ERROR", null, true, false));
    var g = out.groups().getFirst();
    assertEquals("INCONSISTENT", g.status());
    assertEquals(3, g.sampleCount());
    assertEquals(1, g.inconclusiveCount());
  }

  @Test
  void anInconclusiveAttemptPreventsAConsistentClaim() {
    var out =
        check(
            s("PASS", 0, true, false), s("PASS", 0, true, false), s("TIMEOUT", null, true, false));
    assertEquals("INCONCLUSIVE", out.groups().getFirst().status());
    assertEquals(2, out.groups().getFirst().passCount());
    assertEquals(1, out.groups().getFirst().inconclusiveCount());
  }

  @Test
  void missingStaleAndContradictoryExitMetadataAreNotTestFailures() {
    assertEquals("INSUFFICIENT", check(s("PASS", 0, true, false)).groups().getFirst().status());
    var out =
        check(
            s("TIMEOUT", null, true, false),
            s("STALE", 1, false, false),
            s("PASS", 1, true, false),
            s("FAIL", 0, true, false),
            s("FAIL", null, true, false),
            s("PASS", 0, false, false));
    var g = out.groups().getFirst();
    assertEquals("INCONCLUSIVE", g.status());
    assertEquals(6, g.inconclusiveCount());
    assertEquals(0, g.passCount());
    assertEquals(0, g.failCount());
  }

  @Test
  void exactRevisionImageAndCommandKeysSeparateGroupsInFirstAppearanceOrder() {
    var a = s("PASS", 0, true, false);
    var b =
        new VerificationStabilityInput.Sample(
            UUID.randomUUID(),
            CONVERSATION,
            COMMAND,
            "c".repeat(64),
            IMAGE,
            "FAIL",
            1,
            true,
            false);
    var c =
        new VerificationStabilityInput.Sample(
            UUID.randomUUID(),
            CONVERSATION,
            COMMAND,
            REV,
            "sha256:" + "d".repeat(64),
            "FAIL",
            1,
            true,
            false);
    var d =
        new VerificationStabilityInput.Sample(
            UUID.randomUUID(), CONVERSATION, "OTHER_CONTRACT", REV, IMAGE, "PASS", 0, true, false);
    var out = check(b, a, c, d, s("PASS", 0, true, false));
    assertEquals(4, out.groups().size());
    assertEquals("c".repeat(64), out.groups().getFirst().inputRevision());
    assertEquals("CONSISTENT_PASS", out.groups().get(1).status());
    assertEquals("sha256:" + "d".repeat(64), out.groups().get(2).imageId());
    assertEquals("OTHER_CONTRACT", out.groups().getLast().command());
  }

  @Test
  void duplicateAndForeignReportIdsCannotInflateOrLeakAGroup() {
    var a = s("PASS", 0, true, false);
    var foreign =
        new VerificationStabilityInput.Sample(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "FOREIGN_SECRET",
            REV,
            "FOREIGN_SECRET",
            "PASS",
            0,
            true,
            false);
    var out = check(foreign, foreign, a, a);
    assertEquals(
        List.of("OUT_OF_SCOPE", "DUPLICATE", "CONCLUSIVE_PASS", "DUPLICATE"),
        out.checks().stream().map(VerificationStabilityReport.SampleCheck::status).toList());
    assertEquals(1, out.groups().size());
    assertEquals(1, out.groups().getFirst().sampleCount());
    assertEquals("INSUFFICIENT", out.groups().getFirst().status());
    assertFalse(AgentJson.encode(out).contains("FOREIGN_SECRET"));
  }

  @Test
  void truncatedLogsDoNotEraseKnownExitsAndAllListsAreImmutable() {
    var source = new ArrayList<>(List.of(s("PASS", 0, true, true), s("PASS", 0, true, false)));
    var input = new VerificationStabilityInput(CONVERSATION, source);
    source.clear();
    var out = policy.check(input);
    assertEquals("CONSISTENT_PASS", out.groups().getFirst().status());
    assertEquals(1, out.groups().getFirst().truncatedOutputs());
    assertThrows(UnsupportedOperationException.class, () -> out.groups().clear());
    assertThrows(UnsupportedOperationException.class, () -> out.checks().clear());
  }
}
