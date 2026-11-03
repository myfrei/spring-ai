package dev.myfrei.repopilot.research;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.AgentRunControl;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResearchBudgetTest {
  private static final UUID PARENT = UUID.randomUUID(), CHILD = UUID.randomUUID();

  @Test
  void parentAndChildUseOneAtomicCallAndReservationBudget() {
    var budget = new ResearchBudget(new ResearchLimits(2, 3, 3000));
    var parent = budget.control(false, id -> {});
    var child = budget.control(true, id -> {});
    assertThat(parent.beforeModel(PARENT, 700, 384)).isNull();
    parent.afterModel(PARENT, 12, 4, true);
    budget.openChild();
    assertThat(child.beforeModel(CHILD, 800, 384)).isNull();
    child.afterModel(CHILD, 9, 3, true);
    assertThat(parent.beforeModel(PARENT, 1, 384)).isEqualTo("RESEARCH_SHARED_MODEL_LIMIT");
    var snapshot = budget.snapshot();
    assertThat(snapshot.admittedModelCalls()).isEqualTo(2);
    assertThat(snapshot.reservedEstimatedTokens()).isEqualTo(2268);
    assertThat(snapshot.providerInputTokens()).isEqualTo(21);
    assertThat(snapshot.providerOutputTokens()).isEqualTo(7);
    assertThat(snapshot.usageComplete()).isTrue();
  }

  @Test
  void unknownUsageStaysUnknownAndReservationsAreNotRefunded() {
    var budget = new ResearchBudget(new ResearchLimits(8, 12, 1000));
    var control = budget.control(false, id -> {});
    assertThat(control.beforeModel(PARENT, 600, 384)).isNull();
    control.afterModel(PARENT, null, null, false);
    assertThat(control.beforeModel(PARENT, 1, 384)).isEqualTo("RESEARCH_RESERVATION_LIMIT");
    assertThat(budget.snapshot().reservedEstimatedTokens()).isEqualTo(984);
    assertThat(budget.snapshot().providerInputTokens()).isNull();
    assertThat(budget.snapshot().providerOutputTokens()).isNull();
    assertThat(budget.snapshot().usageComplete()).isFalse();
  }

  @Test
  void oneChildThreeChildCallsAndSharedToolAdmissionCannotBeExpanded() {
    var budget = new ResearchBudget(ResearchLimits.DEFAULT);
    budget.openChild();
    assertThatThrownBy(budget::openChild)
        .isInstanceOf(ResearchException.class)
        .hasMessage("RESEARCH_CHILD_LIMIT");
    var child = budget.control(true, id -> {});
    for (int i = 0; i < 3; i++) assertThat(child.beforeModel(CHILD, 10, 384)).isNull();
    assertThat(child.beforeModel(CHILD, 10, 384)).isEqualTo("RESEARCH_CHILD_MODEL_LIMIT");
    for (int i = 0; i < 12; i++) assertThat(child.beforeTool(CHILD, "readFile")).isNull();
    assertThat(budget.control(false, id -> {}).beforeTool(PARENT, "researchRepository"))
        .isEqualTo("RESEARCH_SHARED_TOOL_LIMIT");
  }

  @Test
  void cancellationStopsBothAdmissionPathsAndDoesNotErasePriorAccounting() {
    var budget = new ResearchBudget(ResearchLimits.DEFAULT);
    var parent = budget.control(false, id -> {});
    var child = budget.control(true, id -> {});
    parent.beforeModel(PARENT, 50, 384);
    budget.cancel();
    assertThat(parent.check(PARENT)).isEqualTo("CANCELLED");
    assertThat(child.check(CHILD)).isEqualTo("CANCELLED");
    assertThat(child.beforeTool(CHILD, "readFile")).isEqualTo("CANCELLED");
    assertThat(parent.beforeModel(PARENT, 1, 384)).isEqualTo("CANCELLED");
    assertThatThrownBy(budget::openChild).hasMessage("CANCELLED");
    assertThat(budget.snapshot().admittedModelCalls()).isEqualTo(1);
  }

  @Test
  void simultaneousParentChildAdmissionsCannotOverspend() throws Exception {
    var budget = new ResearchBudget(new ResearchLimits(1, 12, 48000));
    var parent = budget.control(false, id -> {});
    var child = budget.control(true, id -> {});
    var gate = new CountDownLatch(1);
    var accepted = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<?>>();
      for (AgentRunControl control : List.of(parent, child))
        jobs.add(
            executor.submit(
                () -> {
                  try {
                    gate.await();
                    if (control.beforeModel(UUID.randomUUID(), 100, 384) == null)
                      accepted.incrementAndGet();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
      gate.countDown();
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
    }
    assertThat(accepted).hasValue(1);
    assertThat(budget.snapshot().admittedModelCalls()).isEqualTo(1);
  }
}
