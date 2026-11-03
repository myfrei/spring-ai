package dev.myfrei.repopilot.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

class AgentRuntimeHooksTest {
  @Test
  void dynamicPlanContextIsRebuiltBeforeEachModelCallAndTerminalGateRunsBeforeCommit() {
    var model = new AgentFixtures.Model();
    model.script.addAll(
        List.of(AgentFixtures.tool("getProjectInfo", "{}", 1), AgentFixtures.answer()));
    var store = new AgentStore();
    UUID conversation = store.createConversation().id();
    var calls = new AtomicInteger();
    var gate = new AtomicInteger();
    var engine =
        AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
            .withRuntimeHooks(
                id -> "CURRENT_STEP=" + calls.incrementAndGet(),
                id -> {
                  gate.incrementAndGet();
                  return "PLAN_INCOMPLETE";
                });
    var run = engine.run(conversation, "Q");
    assertThat(calls).hasValue(2);
    assertThat(gate).hasValue(1);
    assertThat(model.prompts.getFirst().getInstructions().getFirst().getText())
        .contains("CURRENT_STEP=1");
    assertThat(model.prompts.getLast().getInstructions().getFirst().getText())
        .contains("CURRENT_STEP=2");
    assertThat(run.status()).isEqualTo("STOPPED");
    assertThat(run.stopReason()).isEqualTo("PLAN_INCOMPLETE");
    assertThat(run.answer()).isNull();
    assertThat(store.conversation(conversation).turns()).isEmpty();
    assertThat(store.conversation(conversation).activeRunId()).isNull();
    assertThat(store.messages(run.id()).getLast().role()).isEqualTo("TOOL");
  }

  @Test
  void clonesPreserveContextCompletionGateAndProjectionInEitherConstructionOrder() {
    var model = new AgentFixtures.Model();
    model.script.add(AgentFixtures.answer());
    var store = new AgentStore();
    var seen = new AtomicInteger();
    var engine =
        AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
            .withRuntimeHooks(id -> "SERVER_DYNAMIC_CONTEXT", id -> "PLAN_EVIDENCE_STALE")
            .withPromptProjection(
                (id, system, history, current, budget, estimate) -> {
                  seen.incrementAndGet();
                  assertThat(system.getFirst().getText()).contains("SERVER_DYNAMIC_CONTEXT");
                  var messages = new ArrayList<Message>(system);
                  messages.addAll(current);
                  assertThat(estimate.applyAsInt(messages)).isPositive();
                  return messages;
                })
            .withStopRule(h -> false)
            .withToolsAndInstructions(List.of(), "EXTRA");
    var result = engine.run(store.createConversation().id(), "Q");
    assertThat(seen).hasValue(1);
    assertThat(result.stopReason()).isEqualTo("PLAN_EVIDENCE_STALE");
    assertThat(store.messages(result.id())).hasSize(1);
  }

  @Test
  void nullProjectionAndOversizedProjectionDenyModelAdmission() {
    for (boolean oversized : List.of(false, true)) {
      var model = new AgentFixtures.Model();
      var store = new AgentStore();
      var engine =
          AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
              .withPromptProjection(
                  (id, s, h, c, b, e) ->
                      oversized ? List.of(new SystemMessage("x".repeat(50000))) : null);
      var result = engine.run(store.createConversation().id(), "Q");
      assertThat(result.stopReason()).isEqualTo("CONTEXT_LIMIT");
      assertThat(model.calls).isZero();
      assertThat(result.report().modelCalls()).isZero();
    }
  }

  @Test
  void exceptionOrUnboundedHookFailsClosedAndAlwaysReleasesConversation() {
    for (String kind : List.of("context", "terminal", "projection", "too-large")) {
      var model = new AgentFixtures.Model();
      model.script.add(AgentFixtures.answer());
      var store = new AgentStore();
      var id = store.createConversation().id();
      var engine =
          AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
              .withRuntimeHooks(
                  run -> {
                    if (kind.equals("context")) throw new IllegalStateException("PRIVATE");
                    return kind.equals("too-large") ? "x".repeat(9000) : "";
                  },
                  run -> {
                    if (kind.equals("terminal")) throw new IllegalStateException("PRIVATE");
                    return null;
                  });
      if (kind.equals("projection"))
        engine =
            engine.withPromptProjection(
                (r, s, h, c, b, e) -> {
                  throw new IllegalStateException("PRIVATE");
                });
      var result = engine.run(id, "Q");
      assertThat(result.stopReason()).isEqualTo("ENGINE_ERROR");
      assertThat(store.conversation(id).activeRunId()).isNull();
      assertThat(store.conversation(id).turns()).isEmpty();
      assertThat(AgentJson.encode(store.events(result.id()))).doesNotContain("PRIVATE");
    }
  }

  @Test
  void projectionReceivesWholeImmutableHistoryAndLeavesFullJournalIntact() {
    var model = new AgentFixtures.Model();
    model.script.add(AgentFixtures.answer());
    var store = new AgentStore();
    var id = store.createConversation().id();
    var engine = AgentFixtures.engine(model, new AgentFixtures.Identity(), store);
    var first = engine.run(id, "First");
    model.script.add(AgentFixtures.answer());
    var next =
        engine
            .withPromptProjection(
                (r, s, h, c, b, e) -> {
                  assertThat(h).hasSize(1);
                  assertThat(h.getFirst()).hasSize(2);
                  assertThatThrownBy(() -> h.getFirst().clear())
                      .isInstanceOf(UnsupportedOperationException.class);
                  var messages = new ArrayList<Message>(s);
                  messages.addAll(c);
                  return messages;
                })
            .run(id, "Second");
    assertThat(next.status()).isEqualTo("COMPLETED");
    assertThat(store.messages(first.id())).hasSize(2);
    assertThat(store.conversation(id).turns()).hasSize(2);
    assertThat(model.prompts.getLast().getInstructions()).hasSize(2);
  }
}
