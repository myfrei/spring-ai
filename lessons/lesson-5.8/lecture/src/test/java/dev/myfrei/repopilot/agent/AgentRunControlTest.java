package dev.myfrei.repopilot.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class AgentRunControlTest {
  @Test
  void admissionControlSurvivesEveryCopyAndRejectsBeforeModelInvocation() {
    var model = new AgentFixtures.Model();
    var identity = new AgentFixtures.Identity();
    var store = new AgentStore();
    var seen = new AtomicInteger();
    var control =
        new AgentRunControl() {
          public String beforeModel(UUID id, int input, int output) {
            assertThat(input).isPositive();
            assertThat(output).isEqualTo(384);
            seen.incrementAndGet();
            return "LOCAL_LIMIT";
          }
        };
    var base =
        AgentFixtures.engine(model, identity, store)
            .withRunControl(control)
            .withStopRule(history -> false)
            .withRuntimeHooks(id -> "EXTRA", id -> null)
            .withPromptProjection(
                (id, system, history, current, budget, estimate) -> {
                  var all = new ArrayList<>(system);
                  all.addAll(current);
                  return all;
                })
            .withToolsAndInstructions(List.of(), "BOUNDED");
    var run = base.run(store.createConversation().id(), "QUESTION");
    assertThat(run.stopReason()).isEqualTo("LOCAL_LIMIT");
    assertThat(model.calls).isZero();
    assertThat(seen).hasValue(1);
    assertThat(run.report().modelCalls()).isZero();
  }

  @Test
  void cancellationAfterBlockingModelDoesNotExecuteReturnedCallsAndUsageIsObservedOnce() {
    var model = new AgentFixtures.Model();
    var identity = new AgentFixtures.Identity();
    var store = new AgentStore();
    var cancelled = new AtomicBoolean();
    var observed = new AtomicInteger();
    model.hook = () -> cancelled.set(true);
    var control =
        new AgentRunControl() {
          public String check(UUID id) {
            return cancelled.get() ? "CANCELLED" : null;
          }

          public void afterModel(UUID id, Integer input, Integer output, boolean known) {
            assertThat(known).isTrue();
            assertThat(input).isEqualTo(8);
            assertThat(output).isEqualTo(3);
            observed.incrementAndGet();
          }
        };
    var run =
        AgentFixtures.engine(model, identity, store)
            .withRunControl(control)
            .run(store.createConversation().id(), "QUESTION");
    assertThat(run.stopReason()).isEqualTo("CANCELLED");
    assertThat(run.report().executedTools()).isZero();
    assertThat(store.messages(run.id())).hasSize(1);
    assertThat(observed).hasValue(1);
    assertThat(identity.calls).isEqualTo(2);
  }

  @Test
  void cancellationInBatchCorrelatesAllRepliesButSkipsRemainingTools() {
    var model = new AgentFixtures.Model();
    var identity = new AgentFixtures.Identity();
    var store = new AgentStore();
    var admissions = new AtomicInteger();
    model.script.add(
        AgentFixtures.response(
            "",
            "stop",
            List.of(
                AgentFixtures.call("getProjectInfo", "{}", 1),
                AgentFixtures.call("getProjectInfo", "{}", 2))));
    var control =
        new AgentRunControl() {
          public String beforeTool(UUID id, String name) {
            return admissions.incrementAndGet() == 2 ? "CANCELLED" : null;
          }
        };
    var run =
        AgentFixtures.engine(model, identity, store)
            .withRunControl(control)
            .run(store.createConversation().id(), "QUESTION");
    assertThat(run.stopReason()).isEqualTo("CANCELLED");
    assertThat(run.report().executedTools()).isEqualTo(1);
    AgentEngineTest.assertBalanced(store.messages(run.id()));
    var replies = store.messages(run.id()).stream().flatMap(m -> m.toolResults().stream()).toList();
    assertThat(replies).hasSize(2);
    assertThat(replies.getLast().resultJson()).contains("NOT_EXECUTED");
  }

  @Test
  void failedToolGuardStillClosesTheEntireAcceptedBatch() {
    var model = new AgentFixtures.Model();
    var identity = new AgentFixtures.Identity();
    var store = new AgentStore();
    model.script.add(
        AgentFixtures.response(
            "",
            "stop",
            List.of(
                AgentFixtures.call("getProjectInfo", "{}", 1),
                AgentFixtures.call("getProjectInfo", "{}", 2))));
    var control =
        new AgentRunControl() {
          public String beforeTool(UUID id, String name) {
            throw new IllegalStateException("PRIVATE-CONTROL");
          }
        };
    var run =
        AgentFixtures.engine(model, identity, store)
            .withRunControl(control)
            .run(store.createConversation().id(), "QUESTION");
    assertThat(run.stopReason()).isEqualTo("RUN_CONTROL_FAILED");
    assertThat(run.report().executedTools()).isZero();
    AgentEngineTest.assertBalanced(store.messages(run.id()));
    assertThat(AgentJson.encode(store.events(run.id()))).doesNotContain("PRIVATE-CONTROL");
  }

  @Test
  void failedProviderObservesUnknownUsageAndNeverRefundsItAsZero() {
    var model = new AgentFixtures.Model();
    var identity = new AgentFixtures.Identity();
    var store = new AgentStore();
    var observations = new AtomicInteger();
    model.failure = new IllegalStateException("private provider");
    var control =
        new AgentRunControl() {
          public void afterModel(UUID id, Integer input, Integer output, boolean known) {
            assertThat(input).isNull();
            assertThat(output).isNull();
            assertThat(known).isFalse();
            observations.incrementAndGet();
          }
        };
    var run =
        AgentFixtures.engine(model, identity, store)
            .withRunControl(control)
            .run(store.createConversation().id(), "QUESTION");
    assertThat(run.stopReason()).isEqualTo("MODEL_UNAVAILABLE");
    assertThat(observations).hasValue(1);
    assertThat(identity.calls).isEqualTo(2);
  }

  @Test
  void forkUsesFreshStoreAndResetsParentHooksControlAndInstructions() {
    var model = new AgentFixtures.Model();
    model.script.add(AgentFixtures.answer());
    var identity = new AgentFixtures.Identity();
    var parentStore = new AgentStore();
    var parent =
        AgentFixtures.engine(model, identity, parentStore)
            .withToolsAndInstructions(List.of(), "PARENT-PRIVATE-CONTEXT")
            .withRuntimeHooks(id -> "PARENT-PLAN", id -> "PARENT_BLOCK")
            .withRunControl(
                new AgentRunControl() {
                  public String check(UUID id) {
                    return "CANCELLED";
                  }
                });
    var childStore = new AgentStore();
    var run =
        parent
            .fork(List.of(), childStore, "CHILD-ONLY")
            .run(childStore.createConversation().id(), "FOCUSED-QUESTION");
    assertThat(run.status()).isEqualTo("COMPLETED");
    assertThat(model.prompts).hasSize(1);
    assertThat(model.prompts.getFirst().getContents())
        .contains("CHILD-ONLY", "FOCUSED-QUESTION")
        .doesNotContain("PARENT-PRIVATE-CONTEXT", "PARENT-PLAN");
    assertThatThrownBy(() -> parentStore.run(run.id())).isInstanceOf(AgentProblem.class);
  }
}
