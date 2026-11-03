package dev.myfrei.repopilot.plan;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlanSessionTest {
  @TempDir Path root;
  WorkspaceService workspace;
  PlanSession session;
  UUID run;
  static final String CODE = "src/main/java/Calc.java", TEST = "src/test/java/CalcTest.java";

  @BeforeEach
  void prepare() throws Exception {
    Files.createDirectories(root.resolve("src/main/java"));
    Files.createDirectories(root.resolve("src/test/java"));
    Files.writeString(root.resolve(CODE), "class Calc {\n int value = 1;\n}\n");
    Files.writeString(root.resolve(TEST), "class CalcTest {\n // checks quantity\n}\n");
    workspace = new WorkspaceService(root);
    run = UUID.randomUUID();
    session = new PlanSession(spec(), workspace);
  }

  static PlanSpec.StepSpec step(
      String id, String criterion, String path, List<String> dependencies, boolean required) {
    return new PlanSpec.StepSpec(id, "Read " + id, criterion, path, 1, 3, dependencies, required);
  }

  static PlanSpec spec() {
    return new PlanSpec(
        List.of(
            step("code", "READ_CODE", CODE, List.of(), true),
            step("test", "READ_TEST", TEST, List.of("code"), true)));
  }

  @Test
  void initialImmutableSnapshotAndDependencyOrderAreExplicit() {
    var view = session.view(run);
    assertThat(view.revision()).isEqualTo(1);
    assertThat(view.steps())
        .extracting(PlanSnapshot.StepView::status)
        .containsExactly("PENDING", "PENDING");
    assertThatThrownBy(() -> view.steps().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertCode("PLAN_DEPENDENCY_PENDING", () -> session.start(run, "test"));
    assertThat(session.completionReason(run)).isEqualTo("PLAN_INCOMPLETE");
  }

  @Test
  void completionRequiresActiveStepAndActualSameRunFullCoverage() {
    assertCode("PLAN_STEP_NOT_ACTIVE", () -> session.complete(run, "code"));
    session.start(run, "code");
    assertCode("PLAN_NOT_READ", () -> session.complete(run, "code"));
    workspace.readFile(UUID.randomUUID().toString(), CODE, 1, 3);
    assertCode("PLAN_NOT_READ", () -> session.complete(run, "code"));
    workspace.readFile(run.toString(), CODE, 1, 1);
    workspace.readFile(run.toString(), CODE, 3, 3);
    assertCode("PLAN_NOT_READ", () -> session.complete(run, "code"));
    workspace.readFile(run.toString(), CODE, 2, 2);
    var completed = session.complete(run, "code");
    assertThat(completed.steps().getFirst().status()).isEqualTo("COMPLETED");
    assertThat(completed.steps().getFirst().evidenceHash()).isEqualTo(workspace.currentHash(CODE));
    assertThat(completed.steps().getFirst().evidenceCurrent()).isTrue();
  }

  @Test
  void searchOrModelClaimIsNotReadEvidence() {
    session.start(run, "code");
    workspace.searchText("value", "src", 5);
    assertCode("PLAN_NOT_READ", () -> session.complete(run, "code"));
    var result =
        session.tools().stream()
            .filter(t -> t.name().equals("completePlanStep"))
            .findFirst()
            .orElseThrow()
            .execute(
                "{\"stepId\":\"code\",\"status\":\"COMPLETED\",\"evidenceHash\":\"fake\"}",
                new AgentToolContext(run, UUID.randomUUID()));
    assertThat(result.ok()).isFalse();
    assertThat(session.view(run).steps().getFirst().status()).isEqualTo("ACTIVE");
  }

  @Test
  void currentDependencyEvidenceAndFinalGateAreBothChecked() throws Exception {
    finish("code", CODE);
    session.start(run, "test");
    workspace.readFile(run.toString(), TEST, 1, 3);
    Files.writeString(root.resolve(CODE), "class Calc {\n int value = 2;\n}\n");
    assertCode("PLAN_DEPENDENCY_STALE", () -> session.complete(run, "test"));
    assertThat(session.view(run).steps().getFirst().evidenceCurrent()).isFalse();
  }

  @Test
  void successNeedsAllRequiredStepsAndTheirCurrentDependencies() throws Exception {
    finish("code", CODE);
    finish("test", TEST);
    assertThat(session.completionReason(run)).isNull();
    Files.writeString(root.resolve(CODE), "changed\n");
    assertThat(session.completionReason(run)).isEqualTo("PLAN_EVIDENCE_STALE");
  }

  @Test
  void optionalIndependentPendingStepDoesNotBlockRequiredSuccess() {
    var spec =
        new PlanSpec(
            List.of(
                step("code", "READ_CODE", CODE, List.of(), true),
                step("optional", "READ_TEST", TEST, List.of(), false)));
    session = new PlanSession(spec, workspace);
    finish("code", CODE);
    assertThat(session.completionReason(run)).isNull();
  }

  @Test
  void resettingProgressKeepsRequirementsAndInvalidatesDependentProofs() {
    finish("code", CODE);
    finish("test", TEST);
    var before = session.view(run);
    var revised = session.revise(run, "code", "Recheck after new information");
    assertThat(revised.revision()).isEqualTo(before.revision() + 1);
    assertThat(revised.steps())
        .extracting(PlanSnapshot.StepView::status)
        .containsExactly("PENDING", "PENDING");
    for (int i = 0; i < 2; i++) {
      var a = before.steps().get(i);
      var b = revised.steps().get(i);
      assertThat(b.path()).isEqualTo(a.path());
      assertThat(b.required()).isEqualTo(a.required());
      assertThat(b.dependsOn()).isEqualTo(a.dependsOn());
      assertThat(b.criterion()).isEqualTo(a.criterion());
      assertThat(b.evidenceHash()).isEmpty();
    }
    assertThat(session.completionReason(run)).isEqualTo("PLAN_INCOMPLETE");
  }

  @Test
  void resetIndependentStepLeavesOtherProgressAndNoConcurrentActiveSteps() {
    finish("code", CODE);
    session.start(run, "test");
    assertCode("PLAN_ALREADY_COMPLETED", () -> session.start(run, "code"));
    assertCode("PLAN_STEP_ACTIVE", () -> session.start(run, "test"));
    session.revise(run, "test", "Retry reading test");
    assertThat(session.view(run).steps().getFirst().status()).isEqualTo("COMPLETED");
  }

  @Test
  void staleCompletedStepCanBeReadAgainWithoutBorrowingOldReceipts() throws Exception {
    finish("code", CODE);
    Files.writeString(root.resolve(CODE), "class Calc {\n int value = 2;\n}\n");
    session.start(run, "code");
    assertCode("PLAN_NOT_READ", () -> session.complete(run, "code"));
    workspace.readFile(run.toString(), CODE, 1, 3);
    session.complete(run, "code");
    assertThat(session.view(run).steps().getFirst().evidenceCurrent()).isTrue();
  }

  @Test
  void separateSessionsNeverReuseRunStateOrProof() {
    finish("code", CODE);
    assertCode("PLAN_RUN_MISMATCH", () -> session.view(UUID.randomUUID()));
    var other = new PlanSession(spec(), workspace);
    UUID second = UUID.randomUUID();
    other.start(second, "code");
    assertCode("PLAN_NOT_READ", () -> other.complete(second, "code"));
    assertThat(session.view(run).steps().getFirst().status()).isEqualTo("COMPLETED");
  }

  @Test
  void invalidSpecsRejectCyclesFutureDependenciesMissingRequiredAndCriterionScope() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PlanSpec(
                    List.of(
                        step("test", "READ_TEST", TEST, List.of("code"), true),
                        step("code", "READ_CODE", CODE, List.of(), true))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PlanSpec(List.of(step("code", "READ_CODE", CODE, List.of(), false))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> step("bad", "READ_TEST", CODE, List.of(), true));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> step("code", "RUN_TEST", CODE, List.of(), true));
  }

  @Test
  void transitiveStaleAncestorBlocksStartAndCompletion() throws Exception {
    session =
        new PlanSession(
            new PlanSpec(
                List.of(
                    step("code", "READ_CODE", CODE, List.of(), true),
                    step("middle", "READ_TEST", TEST, List.of("code"), true),
                    step("last", "READ_TEST", TEST, List.of("middle"), true))),
            workspace);
    finish("code", CODE);
    finish("middle", TEST);
    session.start(run, "last");
    Files.writeString(root.resolve(CODE), "changed\n");
    assertCode("PLAN_DEPENDENCY_STALE", () -> session.complete(run, "last"));
    session.revise(run, "last", "Restart after stale ancestor");
    assertCode("PLAN_DEPENDENCY_STALE", () -> session.start(run, "last"));
  }

  @Test
  void maximalAcceptedSnapshotIncludingEscapesFitsBeforeAnyMutation() {
    var specs = new ArrayList<PlanSpec.StepSpec>();
    var ids = new ArrayList<String>();
    String path = "src/main/" + "a".repeat(226) + ".java";
    for (int i = 0; i < 4; i++) {
      String id = "step" + i + "a".repeat(11);
      specs.add(
          new PlanSpec.StepSpec(
              id, "\"".repeat(120), "READ_CODE", path, 99921, 100000, List.copyOf(ids), true));
      ids.add(id);
    }
    var maximal = new PlanSpec(specs);
    var plan = new PlanSession(maximal, workspace);
    var snapshot = plan.view(run);
    assertThat(
            AgentJson.bytes(AgentJson.encode(Map.of("ok", true, "code", "OK", "data", snapshot))))
        .isLessThanOrEqualTo(4096);
  }

  private void finish(String id, String path) {
    session.start(run, id);
    workspace.readFile(run.toString(), path, 1, 3);
    session.complete(run, id);
  }

  static void assertCode(
      String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(PlanException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
