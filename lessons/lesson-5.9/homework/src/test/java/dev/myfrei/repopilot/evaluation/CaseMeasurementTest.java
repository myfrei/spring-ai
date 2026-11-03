package dev.myfrei.repopilot.evaluation;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import static org.assertj.core.api.Assertions.*;
class CaseMeasurementTest {
    @Test void separatesRetrievalPreparationAndBothFirstTextLatencies() {
        var clock = new AtomicLong(100_000_000); var timer = new CaseMeasurement(clock::get);
        clock.set(120_000_000); timer.searchStarted(); clock.set(180_000_000); timer.searchFinished();
        clock.set(200_000_000); timer.prepared(); clock.set(210_000_000); timer.modelStarted();
        clock.set(240_000_000); timer.delta(" \n"); clock.set(350_000_000); timer.delta("first");
        clock.set(400_000_000); timer.delta("later"); clock.set(700_000_000); var time = timer.finish();
        assertThat(time.totalMs()).isEqualTo(600); assertThat(time.preparationMs()).isEqualTo(100);
        assertThat(time.retrievalMs()).isEqualTo(60); assertThat(time.generationMs()).isEqualTo(490);
        assertThat(time.endToEndTtftMs()).isEqualTo(250); assertThat(time.modelTtftMs()).isEqualTo(140);
    }
    @Test void noModelHasNullTtftAndGenerationInsteadOfZero() {
        var clock = new AtomicLong(); var timer = new CaseMeasurement(clock::get); timer.delta("programmatic refusal");
        clock.set(15_000_000); var time = timer.finish();
        assertThat(time.totalMs()).isEqualTo(15); assertThat(time.endToEndTtftMs()).isNull();
        assertThat(time.modelTtftMs()).isNull(); assertThat(time.generationMs()).isNull();
    }
    @Test void missingProviderUsageStaysNullAndRealCountsAreNotEstimated() {
        var timer = new CaseMeasurement(System::nanoTime);
        timer.usage(response(0, 0)); assertThat(timer.inputTokens()).isNull(); assertThat(timer.outputTokens()).isNull();
        timer.usage(response(123, 7)); timer.usage(response(0, 0));
        assertThat(timer.inputTokens()).isEqualTo(123); assertThat(timer.outputTokens()).isEqualTo(7);
    }
    private ChatResponse response(int input, int output) { return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(new DefaultUsage(input, output)).build()); }
}
