package dev.myfrei.repopilot.rerank;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.web.client.RestClient;
import dev.myfrei.repopilot.rag.*;
import static org.assertj.core.api.Assertions.*;

class SpringRerankModelTest {
    private SpringRerankModel model(RerankFixtures.RecordingChat recording,int window) {
        return new SpringRerankModel(ChatClient.builder(recording),RestClient.builder(),"http://127.0.0.1:1","fixture",window);
    }
    @Test void measuredPromptEqualsActualSdkMessagesAndCompleteOptionsDoNotLeakToFinalBuilder() {
        var recording=new RerankFixtures.RecordingChat(); recording.answer=RerankFixtures.scores(RerankFixtures.found().hits());
        var builder=ChatClient.builder(recording).defaultOptions(OllamaChatOptions.builder().temperature(.2).numPredict(256));
        var model=new SpringRerankModel(builder,RestClient.builder(),"http://127.0.0.1:1","fixture",4096);
        var expected=model.prompt(RerankFixtures.QUESTION,RerankFixtures.found().hits());
        var reply=model.score(RerankFixtures.QUESTION,RerankFixtures.found().hits());
        assertThat(recording.received.getInstructions()).extracting(Message::getText).containsExactlyElementsOf(expected.getInstructions().stream().map(Message::getText).toList());
        var options=(OllamaChatOptions)recording.received.getOptions();
        assertThat(options.getTemperature()).isEqualTo(0.0); assertThat(options.getNumCtx()).isEqualTo(4096); assertThat(options.getNumPredict()).isEqualTo(768);
        assertThat(recording.received.getUserMessage().getText()).contains("{literal}","RAW-SECRET-CANDIDATE","chunkId");
        assertThat(reply.providerInputTokens()).isEqualTo(19); assertThat(reply.providerOutputTokens()).isEqualTo(7); assertThat(reply.finishReason()).isEqualTo("stop");
        builder.build().prompt().user("final question").call().content();
        assertThat(recording.received.getOptions().getTemperature()).isEqualTo(.2);
        assertThat(((OllamaChatOptions)recording.received.getOptions()).getNumPredict()).isEqualTo(256);
    }
    @Test void boundaryUsesRenderedJsonSystemAndOutputReserveAndRejectsOneTokenBelow() {
        var recording=new RerankFixtures.RecordingChat();
        int estimate=model(recording,4096).budget(RerankFixtures.QUESTION,RerankFixtures.found().hits()).estimatedInputTokens();
        int exactWindow=estimate+SpringRerankModel.OUTPUT_RESERVE+SpringRerankModel.SAFETY_MARGIN;
        var exact=model(recording,exactWindow); assertThat(exact.budget(RerankFixtures.QUESTION,RerankFixtures.found().hits()).inputBudget()).isEqualTo(estimate);
        assertThatCode(()->exact.score(RerankFixtures.QUESTION,RerankFixtures.found().hits())).doesNotThrowAnyException();
        assertThatThrownBy(()->model(recording,exactWindow-1).score(RerankFixtures.QUESTION,RerankFixtures.found().hits()))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("RERANK_CONTEXT_TOO_LARGE");
        assertThat(recording.calls).isOne();
    }
    @Test void hugeCandidateIsRejectedWithoutSendingAnythingToChatModel() {
        var recording=new RerankFixtures.RecordingChat(); var model=model(recording,4096);
        var large=List.of(RerankFixtures.hit(1,"данные".repeat(5000),.9));
        assertThatThrownBy(()->model.score("Порт?",large)).isInstanceOf(RagProblem.class).extracting("code").isEqualTo("RERANK_CONTEXT_TOO_LARGE");
        assertThat(recording.calls).isZero();
    }
}
