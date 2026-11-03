package dev.myfrei.repopilot.compaction;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.tools.ToolPromptBudget;
import java.util.*;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;

class CompactingProjectionTest {
  private final UUID run = UUID.randomUUID();
  private final List<Message> system =
      List.of(new SystemMessage("SERVER_MODE=ASK; approval=false; CURRENT_PLAN_V1=required"));
  private final ToIntFunction<List<Message>> estimate =
      messages -> new ToolPromptBudget(List.of()).estimate(new Prompt(messages));

  @Test
  void compressesOnlyCompleteToolExchangesPreservingCurrentAndOldProseVerbatim() {
    var history = new ArrayList<Message>();
    history.add(new UserMessage("OLD-CONSTRAINT: не меняй API 😀"));
    history.addAll(exchange("old", "readFile", large("OLD-PAYLOAD")));
    history.add(new AssistantMessage("OLD-HYPOTHESIS: причина пока не доказана."));
    var current = new ArrayList<Message>();
    current.add(new UserMessage("CURRENT-GOAL: изучи код, ничего не записывай"));
    current.addAll(exchange("first", "readFile", large("FIRST-PAYLOAD")));
    current.addAll(exchange("last", "readFile", large("LATEST-PAYLOAD")));
    var before = List.copyOf(current);
    var projection = new CompactingProjection(2400);
    var result = projection.project(run, system, List.of(history), current, 20000, estimate);
    assertThat(result).as(projection.report(run).toString()).isNotNull();
    String rendered = text(result);
    assertThat(rendered)
        .contains(
            "OLD-CONSTRAINT: не меняй API 😀",
            "OLD-HYPOTHESIS: причина пока не доказана.",
            "CURRENT-GOAL: изучи код, ничего не записывай",
            "SERVER_MODE=ASK",
            "LATEST-PAYLOAD",
            "payloadOmitted",
            "UNKNOWN_REQUIRES_FRESH_READ");
    assertThat(rendered).doesNotContain("OLD-PAYLOAD", "FIRST-PAYLOAD");
    assertThat(current).containsExactlyElementsOf(before);
    assertThat(text(history)).contains("OLD-PAYLOAD");
    assertBalanced(result);
    var frame = projection.report(run).frames().getFirst();
    assertThat(frame.admitted()).isTrue();
    assertThat(frame.compactedExchanges()).isEqualTo(2);
    assertThat(frame.omittedCurrentCallIds()).containsExactly("first");
    assertThat(frame.projectedEstimatedTokens())
        .isLessThan(frame.fullEstimatedTokens())
        .isLessThanOrEqualTo(2400);
  }

  @Test
  void multiCallAssistantAndAllItsRepliesDisappearTogether() {
    var calls = List.of(call("one", "readFile"), call("two", "searchText"));
    var current =
        new ArrayList<Message>(
            List.of(
                new UserMessage("GOAL"),
                AssistantMessage.builder().content("").toolCalls(calls).build(),
                reply("one", "readFile", large("ONE")),
                reply("two", "searchText", large("TWO"))));
    current.addAll(exchange("latest", "readFile", large("LATEST")));
    var projection = new CompactingProjection(2300);
    var result = projection.project(run, system, List.of(), current, 20000, estimate);
    assertThat(result).as(projection.report(run).toString()).isNotNull();
    assertBalanced(result);
    assertThat(
            result.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(a -> a.getToolCalls().stream())
                .map(AssistantMessage.ToolCall::id))
        .containsExactly("latest");
    assertThat(projection.report(run).omittedToolCallIds()).containsExactly("one", "two");
  }

  @Test
  void activeSkillBatchesAssistantHypothesesAndLatestExchangeArePinned() {
    var current = new ArrayList<Message>(List.of(new UserMessage("GOAL")));
    current.addAll(exchange("skill", "activateSkill", large("SKILL-INSTRUCTIONS")));
    current.add(
        AssistantMessage.builder()
            .content("UNVERIFIED-THOUGHT: проверить overflow")
            .toolCalls(List.of(call("thought", "readFile")))
            .build());
    current.add(reply("thought", "readFile", large("THOUGHT-PAYLOAD")));
    current.addAll(exchange("old", "readFile", large("OMIT-THIS")));
    current.addAll(exchange("last", "readFile", large("LATEST")));
    var projection = new CompactingProjection(4300);
    var result = projection.project(run, system, List.of(), current, 20000, estimate);
    assertThat(result).as(projection.report(run).toString()).isNotNull();
    assertThat(text(result))
        .contains("SKILL-INSTRUCTIONS", "UNVERIFIED-THOUGHT", "THOUGHT-PAYLOAD", "LATEST")
        .doesNotContain("OMIT-THIS");
    assertBalanced(result);
  }

  @Test
  void belowProtectedMinimumDeniesAdmissionAndNeverFallsBackToDroppingUserText() {
    var current = List.<Message>of(new UserMessage("CURRENT-USER-CONSTRAINT".repeat(30)));
    var projection = new CompactingProjection(1);
    assertThat(
            projection.project(
                run,
                system,
                List.of(List.of(new UserMessage("OLD-USER-CONSTRAINT"))),
                current,
                20000,
                estimate))
        .isNull();
    var frame = projection.report(run).frames().getFirst();
    assertThat(frame.admitted()).isFalse();
    assertThat(frame.compactedExchanges()).isZero();
    assertThat(current.getFirst().getText()).contains("CURRENT-USER-CONSTRAINT");
  }

  @Test
  void fittingPromptRemainsWholeAndRequestedBudgetCannotExceedRealAdmissionLimit() {
    var current = List.<Message>of(new UserMessage("GOAL"));
    var projection = new CompactingProjection(32768);
    var result = projection.project(run, system, List.of(), current, 1000, estimate);
    assertThat(result).isNotNull().containsAll(system).containsAll(current);
    assertThat(projection.report(run).frames().getFirst().effectiveInputBudget()).isEqualTo(1000);
    assertThat(projection.report(run).frames().getFirst().compactedExchanges()).isZero();
  }

  @Test
  void orphanIncompleteDuplicateAndMismatchedRepliesAreRejectedBeforeProjection() {
    for (var current :
        List.of(
            List.<Message>of(new UserMessage("GOAL"), reply("x", "readFile", "{}")),
            List.<Message>of(
                new UserMessage("GOAL"),
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(call("x", "readFile")))
                    .build()),
            List.<Message>of(
                new UserMessage("GOAL"),
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(call("x", "readFile")))
                    .build(),
                reply("x", "searchText", "{}")),
            List.<Message>of(
                new UserMessage("GOAL"),
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(call("x", "readFile"), call("x", "readFile")))
                    .build(),
                reply("x", "readFile", "{}")))) {
      assertThatThrownBy(
              () ->
                  new CompactingProjection(32768)
                      .project(run, system, List.of(), current, 20000, estimate))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("COMPACTION_PROTOCOL");
    }
  }

  @Test
  void projectionIsRunLocalReportsAreImmutableAndOldFramesAreNotMutated() {
    var projection = new CompactingProjection(3000);
    projection.project(run, system, List.of(), List.of(new UserMessage("GOAL")), 20000, estimate);
    var first = projection.report(run);
    projection.project(run, system, List.of(), List.of(new UserMessage("GOAL")), 20000, estimate);
    assertThat(first.frames()).hasSize(1);
    assertThat(projection.report(run).frames()).hasSize(2);
    assertThatThrownBy(() -> first.frames().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                projection.project(
                    UUID.randomUUID(),
                    system,
                    List.of(),
                    List.of(new UserMessage("OTHER")),
                    20000,
                    estimate))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void summariesAreDataMessagesAndNeverInventModelUsageOrAuthorization() {
    var current = new ArrayList<Message>(List.of(new UserMessage("GOAL")));
    current.addAll(exchange("old", "readFile", large("HIDDEN")));
    current.addAll(exchange("last", "readFile", large("LAST")));
    var projection = new CompactingProjection(2300);
    var result = projection.project(run, system, List.of(), current, 20000, estimate);
    assertThat(result).as(projection.report(run).toString()).isNotNull();
    var summary =
        result.stream()
            .filter(m -> m.getText() != null && m.getText().startsWith("COMPACTED_TOOL_DATA"))
            .findFirst()
            .orElseThrow();
    assertThat(summary).isInstanceOf(UserMessage.class);
    assertThat(summary.getText())
        .contains("\"authorization\":false", "\"semanticSummary\":false")
        .doesNotContain("providerInputTokens");
    assertThat(text(result)).contains("CURRENT_PLAN_V1=required");
  }

  static List<Message> exchange(String id, String name, String payload) {
    return List.of(
        AssistantMessage.builder().content("").toolCalls(List.of(call(id, name))).build(),
        reply(id, name, payload));
  }

  static AssistantMessage.ToolCall call(String id, String name) {
    return new AssistantMessage.ToolCall(id, "function", name, "{\"path\":\"src/main/A.java\"}");
  }

  static ToolResponseMessage reply(String id, String name, String payload) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, payload)))
        .build();
  }

  static String large(String marker) {
    return AgentJson.encode(
        Map.of(
            "ok",
            true,
            "code",
            "OK",
            "data",
            Map.of(
                "path",
                "src/main/A.java",
                "fullFileHash",
                "a".repeat(64),
                "startLine",
                1,
                "endLine",
                1,
                "truncated",
                false,
                "rangeComplete",
                true,
                "content",
                marker + "x".repeat(2700))));
  }

  static String text(List<Message> messages) {
    return messages.stream()
        .map(
            m ->
                m instanceof ToolResponseMessage r
                    ? r.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .reduce("", String::concat)
                    : m.getText() == null ? "" : m.getText())
        .reduce("", String::concat);
  }

  static void assertBalanced(List<Message> messages) {
    var pending = new LinkedHashMap<String, String>();
    for (Message message : messages) {
      if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
        assertThat(pending).isEmpty();
        assistant.getToolCalls().forEach(call -> pending.put(call.id(), call.name()));
      } else if (message instanceof ToolResponseMessage replies) {
        replies
            .getResponses()
            .forEach(reply -> assertThat(pending.remove(reply.id())).isEqualTo(reply.name()));
      } else assertThat(pending).isEmpty();
    }
    assertThat(pending).isEmpty();
  }
}
