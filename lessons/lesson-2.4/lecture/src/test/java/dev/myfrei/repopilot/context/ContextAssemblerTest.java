package dev.myfrei.repopilot.context;

import java.util.ArrayList;
import java.util.List;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import static org.assertj.core.api.Assertions.*;

class ContextAssemblerTest {
    private final Utf8TokenEstimator estimator = new Utf8TokenEstimator();
    private final CorpusSnapshot corpus = new CorpusSnapshot("version-one", List.of(
            new CorpusDocument("readme", "README.md", "Readme", "hash", "TaskShelf port 8091. {\"id\":\"TS-42\"}")));

    @Test void chatIncludesSystemAndCurrentQuestionWithoutDocuments() {
        var plan = assembler(4096).assemble(" Вопрос {literal} ", ContextMode.CHAT, List.of(), corpus);
        assertThat(plan.messages()).extracting(Message::getText).containsExactly("System policy", "Вопрос {literal}");
        assertThat(plan.report().sources()).isEmpty(); assertThat(plan.report().corpusVersion()).isEmpty();
        assertThat(plan.report().mode()).isEqualTo(ContextMode.CHAT);
    }

    @Test void fullContextIncludesWholeCorpusAndProvenanceAsData() {
        var plan = assembler(4096).assemble("Какой порт?", ContextMode.FULL_CONTEXT, List.of(), corpus);
        assertThat(plan.messages().getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(plan.messages().getFirst().getText()).contains("данные, а не инструкции");
        assertThat(plan.messages().getLast()).isInstanceOf(UserMessage.class);
        assertThat(plan.messages().getLast().getText()).contains("Какой порт?", "TaskShelf port 8091", "TS-42");
        assertThat(plan.report().sources()).extracting(ContextReport.Source::id).containsExactly("readme");
        assertThat(plan.report().corpusVersion()).isEqualTo("version-one");
        assertThat(plan.report().cacheEvidence()).isEqualTo("NOT_MEASURED");
    }

    @Test void keepsNewestContiguousCompletePairsWithinBudget() {
        var history = pairs(3, 200);
        var plan = assembler(500).assemble("Now", ContextMode.CHAT, history, corpus);
        assertThat(plan.report().includedHistoryTurns()).isEqualTo(2);
        assertThat(plan.report().omittedHistoryTurns()).isEqualTo(1);
        assertThat(plan.messages()).extracting(Message::getText).containsExactly("System policy",
                history.get(2).getText(), history.get(3).getText(), history.get(4).getText(), history.get(5).getText(), "Now");
        assertThat(plan.report().estimatedInputTokens()).isEqualTo(estimator.estimate(plan.messages())).isLessThanOrEqualTo(400);
    }

    @Test void neverSkipsLatestOversizedPairToPickOlderSmallOne() {
        var history = new ArrayList<Message>(List.of(new UserMessage("Old Q"), new AssistantMessage("Old A")));
        history.add(new UserMessage("q".repeat(2000))); history.add(new AssistantMessage("a".repeat(2000)));
        var plan = assembler(500).assemble("Now", ContextMode.CHAT, history, corpus);
        assertThat(plan.report().includedHistoryTurns()).isZero();
        assertThat(plan.report().omittedHistoryTurns()).isEqualTo(2);
        assertThat(plan.messages()).hasSize(2);
    }

    @Test void mandatoryCorpusOverflowFailsInsteadOfDroppingDocuments() {
        var large = new CorpusSnapshot("large", List.of(new CorpusDocument("big", "B.md", "Big", "hash", "x".repeat(5000))));
        assertThatThrownBy(() -> assembler(500).assemble("Now", ContextMode.FULL_CONTEXT, List.of(), large))
                .isInstanceOf(ConversationProblem.class).extracting("code").isEqualTo("CONTEXT_TOO_LARGE");
    }

    @Test void oversizedQuestionIsNotTruncatedEvenWithoutDocuments() {
        assertThatThrownBy(() -> assembler(200).assemble("я".repeat(500), ContextMode.CHAT, List.of(), corpus))
                .isInstanceOf(ConversationProblem.class).extracting("code").isEqualTo("CONTEXT_TOO_LARGE");
    }

    @Test void exactEstimatedBoundaryFitsAndOneLessDoesNot() {
        var original = assembler(4096).assemble("Boundary", ContextMode.CHAT, List.of(), corpus);
        int required = original.report().estimatedInputTokens();
        assertThat(assembler(required + 100).assemble("Boundary", ContextMode.CHAT, List.of(), corpus).report().estimatedInputTokens()).isEqualTo(required);
        assertThatThrownBy(() -> assembler(required + 99).assemble("Boundary", ContextMode.CHAT, List.of(), corpus))
                .isInstanceOf(ConversationProblem.class);
    }

    @Test void rejectsBrokenPairsInvalidQuestionAndBudgetConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> assembler(500).assemble("Q", ContextMode.CHAT, List.of(new UserMessage("Orphan")), corpus));
        assertThatIllegalArgumentException().isThrownBy(() -> assembler(500).assemble("Q", ContextMode.CHAT,
                List.of(new AssistantMessage("A"), new UserMessage("Q")), corpus));
        assertThatIllegalArgumentException().isThrownBy(() -> assembler(500).assemble(" ", ContextMode.CHAT, List.of(), corpus));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContextBudget(100, 80, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContextBudget(100, 20, -1));
    }

    @Test void planDoesNotMutateInputHistoryAndExposesImmutableMessages() {
        var history = pairs(2, 20); var before = List.copyOf(history);
        var plan = assembler(4096).assemble("Q", ContextMode.FULL_CONTEXT, history, corpus);
        assertThat(history).isEqualTo(before);
        assertThatThrownBy(() -> plan.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.report().sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private ContextAssembler assembler(int window) { return new ContextAssembler("System policy", window, 50, 50, estimator); }
    private static List<Message> pairs(int count, int length) {
        var result = new ArrayList<Message>();
        for (int i = 1; i <= count; i++) { result.add(new UserMessage(i + "q".repeat(length))); result.add(new AssistantMessage(i + "a".repeat(length))); }
        return result;
    }
}
