package dev.myfrei.repopilot.agent;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.tasks.TaskCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentContractsTest {
  @Test
  void catalogToolsHaveClosedSchemasAndPublicBoundedJsonWithoutCriteria() {
    var tools = TaskAgentTools.create(new TaskCatalog());
    var registry = new AgentToolRegistry(tools);
    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactly("getProjectInfo", "listTasks", "getTask");
    var context = new AgentToolContext(UUID.randomUUID(), UUID.randomUUID());
    for (var tool : tools) {
      Object parsed = AgentJson.parse(tool.inputSchema());
      assertThat(parsed).isInstanceOf(Map.class);
      assertThat(((Map<?, ?>) parsed).get("additionalProperties")).isEqualTo(false);
      String args =
          switch (tool.name()) {
            case "getTask" -> "{\"taskId\":\"TASK-101\"}";
            default -> "{}";
          };
      String canonical = registry.validateArguments(tool, args);
      var result = tool.execute(canonical, context);
      assertThat(result.ok()).isTrue();
      assertThat(AgentJson.bytes(result.content())).isLessThan(4096);
      assertThat(result.content()).doesNotContain("acceptanceCriteria");
      assertThat(AgentJson.parse(result.content())).isNotNull();
    }
  }

  @Test
  void scalarTypesNullEnumAndClosedKeySetsAreValidatedBeforeExecute() {
    var tools = TaskAgentTools.create(new TaskCatalog());
    var registry = new AgentToolRegistry(tools);
    var list = tools.get(1);
    var task = tools.get(2);
    for (String raw :
        List.of(
            "{\"limit\":1.0}",
            "{\"limit\":null}",
            "{\"limit\":\"2\"}",
            "{\"limit\":0}",
            "{\"limit\":6}",
            "{\"limit\":1,\"extra\":1}",
            "[]",
            "null"))
      assertThatIllegalArgumentException().isThrownBy(() -> registry.validateArguments(list, raw));
    for (String raw : List.of("{}", "{\"taskId\":\"TASK-999\"}", "{\"taskId\":true}"))
      assertThatIllegalArgumentException().isThrownBy(() -> registry.validateArguments(task, raw));
    assertThat(registry.validateArguments(task, " { \"taskId\" : \"TASK-101\" } "))
        .isEqualTo("{\"taskId\":\"TASK-101\"}");
  }

  @Test
  void registryRejectsDuplicateNamesAndSupportsTwelveExplicitToolsForPlans() {
    var tool = AgentFixtures.custom("fixture", (raw, ctx) -> AgentToolResult.success("{}"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AgentToolRegistry(List.of(tool, tool)));
    var tools = new ArrayList<AgentTool>();
    for (int i = 0; i < 12; i++)
      tools.add(AgentFixtures.custom("tool" + i, (raw, ctx) -> AgentToolResult.success("{}")));
    assertThat(new AgentToolRegistry(tools).callbacks()).hasSize(12);
    tools.add(AgentFixtures.custom("thirteenth", (raw, ctx) -> AgentToolResult.success("{}")));
    assertThatIllegalArgumentException().isThrownBy(() -> new AgentToolRegistry(tools));
  }

  @Test
  void storeCapacityRejectsNewRecordsWithoutLeavingAnActiveRunAndExposesOnlyImmutableViews() {
    var store = new AgentStore();
    var conversation = store.createConversation();
    for (int i = 1; i < AgentStore.MAX_CONVERSATIONS; i++) store.createConversation();
    assertThatThrownBy(store::createConversation)
        .isInstanceOf(AgentProblem.class)
        .extracting("code")
        .isEqualTo("AGENT_CAPACITY");
    for (int i = 0; i < AgentStore.MAX_RUNS; i++) {
      var state = store.begin(conversation.id(), "Q");
      store.finish(
          state,
          new AgentRun(state.id, conversation.id(), "STOPPED", "TEST", null, null),
          List.of());
    }
    assertThatThrownBy(() -> store.begin(conversation.id(), "Q"))
        .isInstanceOf(AgentProblem.class)
        .extracting("code")
        .isEqualTo("AGENT_CAPACITY");
    assertThat(store.conversation(conversation.id()).activeRunId()).isNull();
    assertThatThrownBy(() -> conversation.turns().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void runtimeToolFailureIsSanitizedCorrelatedAndDoesNotRetryAutomatically() {
    var tool =
        AgentFixtures.custom(
            "failure",
            (args, ctx) -> {
              throw new IllegalStateException("PRIVATE-FILE-CONTENT");
            });
    var model = new AgentFixtures.Model();
    model.script.addAll(List.of(AgentFixtures.tool("failure", "{}", 1), AgentFixtures.answer()));
    var store = new AgentStore();
    var run =
        new AgentEngine(model, new AgentFixtures.Identity(), List.of(tool), store, "fixture", 4096)
            .run(store.createConversation().id(), "Q");
    assertThat(run.status()).isEqualTo("COMPLETED");
    assertThat(run.report().executedTools()).isOne();
    assertThat(run.report().successfulTools()).isZero();
    assertThat(model.calls).isEqualTo(2);
    String journal = AgentJson.encode(store.messages(run.id()));
    assertThat(journal).contains("TOOL_EXECUTION_FAILED").doesNotContain("PRIVATE-FILE");
    AgentEngineTest.assertBalanced(store.messages(run.id()));
  }

  @Test
  void canonicalReceiptsIgnoreArgumentWhitespaceAndAreDeliveredAsImmutableBoundedSnapshots() {
    var model = new AgentFixtures.Model();
    model.script.addAll(
        List.of(
            AgentFixtures.tool("getTask", "{\"taskId\":\"TASK-101\"}", 1),
            AgentFixtures.tool("getTask", " { \"taskId\" : \"TASK-101\" } ", 2)));
    var store = new AgentStore();
    var receipts = new ArrayList<ToolObservation>();
    var run =
        AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
            .withStopRule(
                history -> {
                  if (history.size() == 2) {
                    receipts.addAll(history);
                    return true;
                  }
                  return false;
                })
            .run(store.createConversation().id(), "Q");
    assertThat(run.stopReason()).isEqualTo("NO_PROGRESS");
    assertThat(receipts).hasSize(2);
    assertThat(receipts.get(0)).isEqualTo(receipts.get(1));
    AgentEngineTest.assertBalanced(store.messages(run.id()));
  }

  @Test
  void reusedCallIdAcrossModelTurnsStopsWithoutOrphaningThePriorSuccessfulResult() {
    var model = new AgentFixtures.Model();
    model.script.addAll(
        List.of(
            AgentFixtures.tool("getProjectInfo", "{}", 1),
            AgentFixtures.tool("getTask", "{\"taskId\":\"TASK-101\"}", 1)));
    var store = new AgentStore();
    var run =
        AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
            .run(store.createConversation().id(), "Q");
    assertThat(run.stopReason()).isEqualTo("TOOL_PROTOCOL");
    assertThat(run.report().modelCalls()).isEqualTo(2);
    assertThat(run.report().executedTools()).isOne();
    assertThat(store.messages(run.id())).hasSize(3);
    AgentEngineTest.assertBalanced(store.messages(run.id()));
  }

  @Test
  void oversizedProviderArgumentsAreRejectedBeforeRawJournalOrToolExecution() {
    var model = new AgentFixtures.Model();
    model.script.add(
        AgentFixtures.tool("getTask", "{\"taskId\":\"" + "x".repeat(100000) + "\"}", 1));
    var store = new AgentStore();
    var run =
        AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
            .run(store.createConversation().id(), "Q");
    assertThat(run.stopReason()).isEqualTo("TOOL_PROTOCOL");
    assertThat(run.report().toolCalls()).isZero();
    assertThat(run.report().executedTools()).isZero();
    assertThat(store.messages(run.id())).hasSize(1);
    assertThat(AgentJson.bytes(AgentJson.encode(store.messages(run.id())))).isLessThan(4096);
  }

  @Test
  void metadataEventsHashModelControlledCallIdsAndDoNotReflectUnknownToolNames() {
    var model = new AgentFixtures.Model();
    model.script.add(
        AgentFixtures.response(
            "",
            "stop",
            List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "PRIVATE-QUESTION", "function", "PRIVATE_QUESTION", "{}"))));
    var store = new AgentStore();
    var run =
        AgentFixtures.engine(model, new AgentFixtures.Identity(), store)
            .run(store.createConversation().id(), "Q");
    assertThat(run.stopReason()).isEqualTo("TOOL_NOT_ALLOWED");
    String events = AgentJson.encode(store.events(run.id()));
    assertThat(events)
        .doesNotContain("PRIVATE-QUESTION", "PRIVATE_QUESTION")
        .contains("callIdHash", "UNKNOWN");
    assertThat(AgentJson.encode(store.messages(run.id())))
        .contains("PRIVATE-QUESTION", "PRIVATE_QUESTION");
    AgentEngineTest.assertBalanced(store.messages(run.id()));
  }
}
