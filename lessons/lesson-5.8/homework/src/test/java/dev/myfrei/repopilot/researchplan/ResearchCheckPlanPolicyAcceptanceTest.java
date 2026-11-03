package dev.myfrei.repopilot.researchplan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ResearchCheckPlanPolicyAcceptanceTest {
  static final UUID PARENT = UUID.randomUUID();
  static final String H = "a".repeat(64), OLD = "b".repeat(64);
  final ResearchCheckPlanPolicy policy = new ResearchCheckPlanPolicy();

  static ResearchCheckPlanInput.Finding f(String path, int start, int end) {
    return new ResearchCheckPlanInput.Finding(path, H, start, end);
  }

  static ResearchCheckPlanInput.ParentRead r(String path, int start, int end) {
    return new ResearchCheckPlanInput.ParentRead(PARENT, path, H, start, end);
  }

  ResearchCheckPlanReport check(
      List<ResearchCheckPlanInput.Finding> findings,
      List<ResearchCheckPlanInput.ParentRead> reads,
      Map<String, String> hashes,
      int budget) {
    return policy.check(new ResearchCheckPlanInput(PARENT, findings, reads, hashes, budget));
  }

  static List<String> ranges(ResearchCheckPlanReport out) {
    return out.reads().stream()
        .map(r -> r.path() + ":" + r.startLine() + "-" + r.endLine())
        .toList();
  }

  @Test
  void aFreshUnreadFindingCreatesAnExactVersionPinnedReadTask() {
    var out = check(List.of(f("src/A.java", 5, 14)), List.of(), Map.of("src/A.java", H), 80);
    assertEquals(PARENT, out.parentRunId());
    assertEquals(List.of("src/A.java:5-14"), ranges(out));
    assertEquals(H, out.reads().getFirst().fullFileHash());
    assertEquals("NEEDS_READ", out.findings().getFirst().status());
    assertEquals(10, out.findings().getFirst().missingLines());
    assertEquals(10, out.requiredLines());
    assertEquals(10, out.plannedLines());
    assertEquals(0, out.deferredLines());
    assertTrue(out.complete());
  }

  @Test
  void overlappingAndAdjacentFindingsShareOneUnionWithoutDoubleCounting() {
    var out =
        check(
            List.of(f("src/A.java", 1, 10), f("src/A.java", 8, 20), f("src/A.java", 21, 30)),
            List.of(),
            Map.of("src/A.java", H),
            160);
    assertEquals(List.of("src/A.java:1-30"), ranges(out));
    assertEquals(30, out.requiredLines());
    assertEquals(
        List.of(10, 13, 10),
        out.findings().stream().map(ResearchCheckPlanReport.FindingCheck::missingLines).toList());
    assertEquals(
        List.of(0, 1, 2),
        out.findings().stream().map(ResearchCheckPlanReport.FindingCheck::index).toList());
  }

  @Test
  void alreadyReadRangesAreMergedAndSubtractedWithoutClosingUnreadHoles() {
    var out =
        check(
            List.of(f("src/A.java", 1, 20)),
            List.of(
                r("src/A.java", 1, 5),
                r("src/A.java", 8, 10),
                r("src/A.java", 11, 12),
                r("src/A.java", 18, 80)),
            Map.of("src/A.java", H),
            160);
    assertEquals(List.of("src/A.java:6-7", "src/A.java:13-17"), ranges(out));
    assertEquals(7, out.requiredLines());
    assertEquals(7, out.plannedLines());
  }

  @Test
  void foreignRunsVersionsAndOtherPathsDoNotProveParentReading() {
    var reads =
        List.of(
            new ResearchCheckPlanInput.ParentRead(UUID.randomUUID(), "src/A.java", H, 1, 20),
            new ResearchCheckPlanInput.ParentRead(PARENT, "src/A.java", OLD, 1, 20),
            r("src/B.java", 1, 20),
            r("src/A.java", 8, 9));
    var out = check(List.of(f("src/A.java", 1, 20)), reads, Map.of("src/A.java", H), 160);
    assertEquals(List.of("src/A.java:1-7", "src/A.java:10-20"), ranges(out));
    assertEquals(18, out.requiredLines());
  }

  @Test
  void conflictingUnavailableAndStaleFindingsNeverTurnIntoReadsOfAnotherVersion() {
    var findings =
        List.of(
            f("src/A.java", 1, 5),
            new ResearchCheckPlanInput.Finding("src/A.java", OLD, 6, 10),
            f("src/B.java", 1, 5));
    var unavailable = check(findings, List.of(), Map.of("src/A.java", H), 160);
    assertEquals(
        List.of("VERSION_CONFLICT", "VERSION_CONFLICT", "UNAVAILABLE"),
        unavailable.findings().stream().map(ResearchCheckPlanReport.FindingCheck::status).toList());
    assertTrue(unavailable.reads().isEmpty());
    assertFalse(unavailable.complete());
    var stale = check(List.of(f("src/A.java", 1, 5)), List.of(), Map.of("src/A.java", OLD), 160);
    assertEquals("STALE", stale.findings().getFirst().status());
    assertEquals(0, stale.plannedLines());
    assertFalse(stale.complete());
  }

  @Test
  void mergedCoverageIsSplitAtEightyLinesAndTheLastTaskUsesTheRemainingBudget() {
    var out =
        check(
            List.of(f("src/A.java", 1, 80), f("src/A.java", 81, 160), f("src/A.java", 161, 240)),
            List.of(),
            Map.of("src/A.java", H),
            100);
    assertEquals(List.of("src/A.java:1-80", "src/A.java:81-100"), ranges(out));
    assertEquals(240, out.requiredLines());
    assertEquals(100, out.plannedLines());
    assertEquals(140, out.deferredLines());
    assertFalse(out.complete());
    assertTrue(out.reads().stream().allMatch(r -> r.endLine() - r.startLine() + 1 <= 80));
  }

  @Test
  void emptyOrFullyCoveredFindingsNeedNoExecutionsAndInputsAndOutputsAreImmutable() {
    var empty = check(List.of(), List.of(), Map.of(), 1);
    assertTrue(empty.complete());
    assertEquals(0, empty.requiredLines());
    assertTrue(empty.findings().isEmpty());
    var findings = new ArrayList<>(List.of(f("src/A.java", 1, 10)));
    var reads = new ArrayList<>(List.of(r("src/A.java", 1, 5), r("src/A.java", 6, 10)));
    var hashes = new HashMap<>(Map.of("src/A.java", H));
    var input = new ResearchCheckPlanInput(PARENT, findings, reads, hashes, 1);
    findings.clear();
    reads.clear();
    hashes.clear();
    var out = policy.check(input);
    assertEquals("ALREADY_READ", out.findings().getFirst().status());
    assertTrue(out.complete());
    assertTrue(out.reads().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> out.findings().clear());
    assertThrows(UnsupportedOperationException.class, () -> out.reads().clear());
  }

  @Test
  void boundedParentHolesStayBelowTheTaskCapAndPathsKeepFirstFindingOrder() {
    var parentReads =
        IntStream.rangeClosed(1, 8).mapToObj(i -> r("src/A.java", i * 2, i * 2)).toList();
    var out =
        check(
            List.of(f("src/A.java", 1, 80), f("src/A.java", 81, 160), f("src/A.java", 161, 240)),
            parentReads,
            Map.of("src/A.java", H),
            160);
    assertTrue(out.reads().size() <= 16);
    assertEquals(232, out.requiredLines());
    assertEquals(160, out.plannedLines());
    var planned =
        out.reads().stream()
            .flatMapToInt(r -> IntStream.rangeClosed(r.startLine(), r.endLine()))
            .boxed()
            .toList();
    var expected =
        IntStream.rangeClosed(1, 240)
            .filter(i -> !(i <= 16 && i % 2 == 0))
            .limit(160)
            .boxed()
            .toList();
    assertEquals(expected, planned);
    var ordered =
        check(
            List.of(f("src/B.java", 100, 179), f("src/A.java", 1, 80), f("src/A.java", 81, 160)),
            List.of(),
            Map.of("src/A.java", H, "src/B.java", H),
            160);
    assertEquals(List.of("src/B.java:100-179", "src/A.java:1-80"), ranges(ordered));
  }
}
