package dev.myfrei.repopilot.journalrecall;

import static org.junit.jupiter.api.Assertions.*;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolRecallPolicyAcceptanceTest {
  static final UUID RUN = UUID.randomUUID();
  static final String H = "a".repeat(64), OTHER = "b".repeat(64);
  final ToolRecallPolicy policy = new ToolRecallPolicy();

  static RecallInput.Candidate c(String id, String raw) {
    return new RecallInput.Candidate(RUN, id, "readFile", true, "OK", raw, "src/A.java", H, H);
  }

  RecallReport check(List<String> ids, int budget, RecallInput.Candidate... candidates) {
    return policy.check(new RecallInput(RUN, ids, List.of(candidates), budget));
  }

  static void statuses(RecallReport out, String... codes) {
    assertEquals(List.of(codes), out.checks().stream().map(RecallReport.Check::status).toList());
    assertEquals(Arrays.stream(codes).allMatch("RECALLED"::equals), out.allRecalled());
    for (var item : out.checks())
      if (!item.status().equals("RECALLED")) assertNull(item.resultJson());
  }

  @Test
  void returnsWholeOriginalPayloadsInRequestOrderWithExactByteAccounting() {
    var a = c("a", "{\"text\":\"Привет\"}");
    var b = c("b", "{\"x\":1}");
    var out = check(List.of("b", "a"), 4096, a, b);
    statuses(out, "RECALLED", "RECALLED");
    assertEquals(RUN, out.runId());
    assertEquals(
        List.of(b.resultJson(), a.resultJson()),
        out.checks().stream().map(RecallReport.Check::resultJson).toList());
    int bytes = AgentJson.bytes(a.resultJson()) + AgentJson.bytes(b.resultJson());
    assertEquals(bytes, out.usedBytes());
    assertEquals(4096 - bytes, out.remainingBytes());
    assertTrue(out.checks().stream().allMatch(i -> i.toolCode().equals("OK")));
  }

  @Test
  void duplicateNamesAndMissingEntriesDoNotConsumeBudget() {
    var a = c("a", "{}");
    var out = check(List.of("missing", "missing", "a", "a"), 10, a);
    statuses(out, "NOT_FOUND", "DUPLICATE", "RECALLED", "DUPLICATE");
    assertEquals(2, out.usedBytes());
    assertEquals(8, out.remainingBytes());
  }

  @Test
  void foreignRunsDeniedToolsAndFailedResultsNeverExposeTheirBodies() {
    var foreign =
        new RecallInput.Candidate(
            UUID.randomUUID(),
            "foreign",
            "gitDiff",
            false,
            "PRIVATE_CODE",
            "{\"secret\":1}",
            null,
            null,
            null);
    var denied =
        new RecallInput.Candidate(
            RUN, "denied", "gitDiff", false, "PRIVATE_CODE", "{\"secret\":2}", null, null, null);
    var failed =
        new RecallInput.Candidate(
            RUN, "failed", "readFile", false, "READ_FAILED", "{\"secret\":3}", null, null, null);
    var out = check(List.of("foreign", "denied", "failed"), 1, foreign, denied, failed);
    statuses(out, "OUT_OF_SCOPE", "TOOL_DENIED", "FAILED_TOOL");
    assertNull(out.checks().getFirst().toolCode());
    assertNull(out.checks().get(1).toolCode());
    assertEquals("READ_FAILED", out.checks().getLast().toolCode());
    assertEquals(0, out.usedBytes());
  }

  @Test
  void unavailableAndStaleTakePriorityOverAnInsufficientBudget() {
    var unavailable =
        new RecallInput.Candidate(
            RUN, "unavailable", "readFile", true, "OK", "{}", "src/A.java", H, null);
    var stale =
        new RecallInput.Candidate(
            RUN, "stale", "readFile", true, "OK", "{}", "src/B.java", H, OTHER);
    var out = check(List.of("unavailable", "stale"), 1, unavailable, stale);
    statuses(out, "UNAVAILABLE", "STALE");
    assertEquals(0, out.usedBytes());
  }

  @Test
  void oversizedResultDoesNotPreventALaterSmallResult() {
    var large = c("large", "{\"text\":\"" + "x".repeat(100) + "\"}");
    var small = c("small", "{}");
    var out = check(List.of("large", "small"), 2, large, small);
    statuses(out, "OVER_BUDGET", "RECALLED");
    assertEquals("{}", out.checks().getLast().resultJson());
    assertEquals(2, out.usedBytes());
    assertEquals(0, out.remainingBytes());
  }

  @Test
  void utf8BoundaryUsesBytesAndNeverSlicesJsonOrCharacters() {
    var candidate = c("unicode", "{\"text\":\"я😀\"}");
    int bytes = AgentJson.bytes(candidate.resultJson());
    assertTrue(bytes > candidate.resultJson().length());
    statuses(check(List.of("unicode"), bytes - 1, candidate), "OVER_BUDGET");
    var fits = check(List.of("unicode"), bytes, candidate);
    statuses(fits, "RECALLED");
    assertEquals(candidate.resultJson(), fits.checks().getFirst().resultJson());
    assertEquals(0, fits.remainingBytes());
  }

  @Test
  void sourceTruncationFlagsRemainUnchangedAndAreNotPromotedToFullReading() {
    String raw =
        "{\"ok\":true,\"code\":\"OK\",\"data\":{\"truncated\":true,\"rangeComplete\":false,\"content\":\"partial\"}}";
    var out = check(List.of("partial"), 4096, c("partial", raw));
    statuses(out, "RECALLED");
    assertEquals(raw, out.checks().getFirst().resultJson());
    assertEquals(
        Boolean.TRUE,
        ((Map<?, ?>)
                ((Map<?, ?>) AgentJson.parse(out.checks().getFirst().resultJson())).get("data"))
            .get("truncated"));
  }

  @Test
  void immutableCopiesKeepInputOrderAndNeverSpendBytesOnDuplicates() {
    var ids = new ArrayList<>(List.of("b", "a", "b"));
    var candidates = new ArrayList<>(List.of(c("a", "{}"), c("b", "[]")));
    var input = new RecallInput(RUN, ids, candidates, 4);
    ids.clear();
    candidates.clear();
    var out = policy.check(input);
    statuses(out, "RECALLED", "RECALLED", "DUPLICATE");
    assertEquals(
        List.of("b", "a", "b"), out.checks().stream().map(RecallReport.Check::callId).toList());
    assertEquals(4, out.usedBytes());
    assertThrows(UnsupportedOperationException.class, () -> out.checks().clear());
  }
}
