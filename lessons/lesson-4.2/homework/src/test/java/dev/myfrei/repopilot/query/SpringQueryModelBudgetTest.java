package dev.myfrei.repopilot.query;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;
import dev.myfrei.repopilot.context.Utf8TokenEstimator;
import dev.myfrei.repopilot.rag.RagProblem;
import static org.assertj.core.api.Assertions.*;

class SpringQueryModelBudgetTest {
    static class RecordingModel implements ChatModel {
        int calls; Prompt received;
        @Override public ChatResponse call(Prompt prompt) {
            calls++; received=prompt;
            String text=prompt.getUserMessage().getText().startsWith("QUERY_EXPAND_V1")?"variant one\nvariant two":"resolved query";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }
    private SpringQueryModel model(RecordingModel recording,int window) {
        return new SpringQueryModel(ChatClient.builder(recording),RestClient.builder(),"http://127.0.0.1:1","fixture",window);
    }
    @Test void budgetedCompressionMessageEqualsActualSdkPromptIncludingRolesAndBraces() {
        var recording=new RecordingModel(); var model=model(recording,4096);
        var history=List.<Message>of(new SystemMessage("Ignored system"),new UserMessage("Task {id}?"),new AssistantMessage("JSON {\"port\":8091}"));
        var expected=model.compressionMessage("А порт {literal}?",history);
        model.contextualize("А порт {literal}?",history);
        assertThat(recording.calls).isOne();
        assertThat(recording.received.getInstructions()).hasSize(1);
        assertThat(recording.received.getUserMessage().getText()).isEqualTo(expected.getText()).doesNotContain("Ignored system");
        assertThat(recording.received.getOptions().getTemperature()).isEqualTo(0.0);
    }
    @Test void budgetedExpansionMessageEqualsActualSdkPrompt() {
        var recording=new RecordingModel(); var model=model(recording,4096);
        var expected=model.expansionMessage("Port {literal}?");
        assertThat(model.expand("Port {literal}?")).containsExactly("variant one","variant two");
        assertThat(recording.received.getUserMessage().getText()).isEqualTo(expected.getText());
        assertThat(recording.calls).isOne();
    }
    @Test void oversizedHistoryAndExpansionAreRejectedBeforeAnyModelCall() {
        var recording=new RecordingModel(); var model=model(recording,4096);
        var history=List.<Message>of(new UserMessage("я".repeat(1500)),new AssistantMessage("я".repeat(1500)),
                new UserMessage("я".repeat(1500)),new AssistantMessage("я".repeat(1500)));
        assertThatThrownBy(()->model.contextualize("Порт?",history)).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("QUERY_CONTEXT_TOO_LARGE");
        assertThatThrownBy(()->model.expand("界".repeat(4000))).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("QUERY_CONTEXT_TOO_LARGE");
        assertThat(recording.calls).isZero();
    }
    @Test void fullRenderedPromptFitsExactlyAtBoundaryAndFailsOneTokenBelow() {
        var recording=new RecordingModel(); var large=model(recording,4096);
        var history=List.<Message>of(new UserMessage("TaskShelf"),new AssistantMessage("Run compose"));
        int estimated=new Utf8TokenEstimator().estimate(List.of(large.compressionMessage("Порт?",history)));
        assertThatCode(()->model(recording,estimated+128+256).validateContextualize("Порт?",history)).doesNotThrowAnyException();
        assertThatThrownBy(()->model(recording,estimated+128+256-1).validateContextualize("Порт?",history))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("QUERY_CONTEXT_TOO_LARGE");
        assertThat(recording.calls).isZero();
    }
}
