package dev.myfrei.repopilot.homework;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import static org.assertj.core.api.Assertions.*;

class PinnedContextHomeworkAcceptanceTest {
    private final CorpusSnapshot corpus = new CorpusSnapshot("fixture", List.of(new CorpusDocument("readme","README.md","Title","hash","text")));
    private PinnedContextAssembler planner(int inputBudget) { return new PinnedContextAssembler(new ContextAssembler("Policy", inputBudget+100, 50, 50, new Utf8TokenEstimator()), new Utf8TokenEstimator()); }
    private List<Message> history() { return List.of(new UserMessage("old fact"), new AssistantMessage("valuable fact"), new UserMessage("q".repeat(1000)), new AssistantMessage("a".repeat(1000))); }
    @Test void pinnedOldPairSurvivesWhenNewestPairCannotFit() {
        var plan = planner(200).assemble("Now", ContextMode.CHAT, history(), corpus, List.of(0));
        assertThat(plan.messages()).extracting(Message::getText).containsExactly("Policy","old fact","valuable fact","Now");
        assertThat(plan.report().includedHistoryTurns()).isEqualTo(1); assertThat(plan.report().omittedHistoryTurns()).isEqualTo(1);
    }
    @Test void keepsChronologyEvenWhenPinsArriveInReverseOrder() {
        var history = List.<Message>of(new UserMessage("Q0"),new AssistantMessage("A0"),new UserMessage("Q1"),new AssistantMessage("A1"),new UserMessage("Q2"),new AssistantMessage("A2"));
        var plan = planner(1000).assemble("Now",ContextMode.CHAT,history,corpus,List.of(2,0));
        assertThat(plan.messages()).extracting(Message::getText).containsExactly("Policy","Q0","A0","Q1","A1","Q2","A2","Now");
    }
    @Test void neverSilentlyDropsAnOversizedPin() {
        assertThatThrownBy(() -> planner(100).assemble("Now",ContextMode.CHAT,history(),corpus,List.of(1)))
            .isInstanceOf(ConversationProblem.class).extracting("code").isEqualTo("PINNED_CONTEXT_TOO_LARGE");
    }
    @Test void rejectsInvalidAndDuplicatePinsAndOrphanMessages() {
        for (var pins : List.of(List.of(-1),List.of(2),List.of(0,0))) assertThatThrownBy(() -> planner(1000).assemble("Now",ContextMode.CHAT,history(),corpus,pins)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner(1000).assemble("Now",ContextMode.CHAT,List.of(new UserMessage("orphan")),corpus,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyPinsRetainRecentHistoryBaselineAndDoNotMutateInput() {
        var history = history(); var plan = planner(200).assemble("Now",ContextMode.CHAT,history,corpus,List.of());
        assertThat(plan.messages()).hasSize(2); assertThat(history).hasSize(4);
        assertThat(plan.report().estimatedInputTokens()).isLessThanOrEqualTo(200);
    }
}
