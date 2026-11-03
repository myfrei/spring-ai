package dev.myfrei.repopilot.advisor;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
class SnapshotRagAdvisorTest {
    @Test void expandsExactlyPreparedPlanOnceAndPreservesOriginalRequest() {
        var plan = AdvisorFixtures.plan(); var original = new ChatClientRequest(new Prompt(plan.baseMessages()), Map.of("owner", "test"));
        var result = new SnapshotRagAdvisor(plan).before(original, null);
        assertThat(original.prompt().getUserMessage().getText()).isEqualTo(AdvisorFixtures.QUESTION);
        assertThat(result.prompt().getUserMessage().getText()).isEqualTo(RagPrompt.render(AdvisorFixtures.QUESTION, plan.references()));
        assertThat(result.context()).containsEntry("owner", "test").containsEntry(SnapshotRagAdvisor.APPLIED, true).containsEntry(SnapshotRagAdvisor.REPORT, plan.report());
    }
    @Test void rejectsDoubleAugmentationAndChangedBudgetInput() {
        var plan = AdvisorFixtures.plan(); var advisor = new SnapshotRagAdvisor(plan); var request = new ChatClientRequest(new Prompt(plan.baseMessages()), Map.of());
        assertThatThrownBy(() -> advisor.before(advisor.before(request, null), null)).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("DUPLICATE_RAG");
        assertThatThrownBy(() -> advisor.before(new ChatClientRequest(new Prompt(new UserMessage("different")), Map.of()), null))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("PROMPT_CHANGED");
    }
    @Test void refusesEmptySnapshotInsteadOfCallingModelWithUnbudgetedPrompt() {
        var empty = AdvisorFixtures.planner().plan(AdvisorFixtures.QUESTION, List.of(), new RagSearchResult(AdvisorFixtures.retrieval().manifest(), List.of()));
        assertThatThrownBy(() -> new SnapshotRagAdvisor(empty)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void afterAttachesReportWithoutChangingTextOrFinishReason() {
        var plan = AdvisorFixtures.plan(); var source = AdvisorFixtures.response("Answer", "stop");
        var response = new SnapshotRagAdvisor(plan).after(new ChatClientResponse(source, Map.of(SnapshotRagAdvisor.REPORT, plan.report())), null);
        assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("Answer");
        assertThat(response.chatResponse().getResult().getMetadata().getFinishReason()).isEqualTo("stop");
        assertThat((Object) response.chatResponse().getMetadata().get(SnapshotRagAdvisor.REPORT)).isEqualTo(plan.report());
    }
}
