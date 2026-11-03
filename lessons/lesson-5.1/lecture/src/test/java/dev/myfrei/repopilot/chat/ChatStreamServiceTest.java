package dev.myfrei.repopilot.chat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatStreamServiceTest {
    private ChatModel model;
    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        this.model = mock(ChatModel.class);
        when(this.model.getOptions()).thenReturn(ChatOptions.builder().model("test-model").build());
        this.service = new ChatStreamService(ChatClient.builder(this.model), "System instruction",
                Duration.ofSeconds(30), Duration.ofSeconds(90));
        clearInvocations(this.model);
    }

    @Test
    void staysLazyUntilSubscribedAndKeepsSeparatePromptRoles() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.just(part("Ответ", ""), part("", "stop")));
        var events = this.service.stream("  Вопрос  ");
        verifyNoInteractions(this.model);
        StepVerifier.create(events).expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("Ответ"),
                ChatStreamEvent.completed()).verifyComplete();
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(this.model).stream(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("Вопрос");
        assertThat(prompt.getValue().getSystemMessage().getText()).isEqualTo("System instruction");
    }

    @Test
    void preservesWhitespaceFragmentsAndRequiresStopMetadata() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.just(
                part("public", ""), part(" ", ""), part("record", ""), part("", "stop")));
        StepVerifier.create(this.service.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("public"), ChatStreamEvent.delta(" "),
                        ChatStreamEvent.delta("record"), ChatStreamEvent.completed()).verifyComplete();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsInvalidInputBeforeOpeningStream(String text) {
        assertThatThrownBy(() -> this.service.stream(text)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.model);
    }

    @Test
    void rejectsOversizedInputBeforeOpeningStream() {
        assertThatThrownBy(() -> this.service.stream("x".repeat(4001))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.model);
    }

    @Test
    void normalEofWithoutProviderFinishIsNotSuccess() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.just(part("Часть", "")));
        StepVerifier.create(this.service.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("Часть"))
                .expectNextMatches(e -> e.type().equals("failed") && e.code().equals("UPSTREAM_INCOMPLETE"))
                .verifyComplete();
    }

    @Test
    void emptyStopIsFailure() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.just(part("", "stop")));
        StepVerifier.create(this.service.stream("Вопрос")).expectNext(ChatStreamEvent.started())
                .expectNextMatches(e -> e.code().equals("EMPTY_RESPONSE")).verifyComplete();
    }

    @Test
    void outputLimitKeepsPartialTextButNeverReportsCompleted() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.just(part("Часть", "length")));
        StepVerifier.create(this.service.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("Часть"))
                .expectNextMatches(e -> e.code().equals("OUTPUT_LIMIT")).verifyComplete();
    }

    @Test
    void turnsMidStreamErrorIntoOneFailedEventWithoutLeakingDetails() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(part("Часть", "")), Flux.error(new IllegalStateException("private-provider-data"))));
        StepVerifier.create(this.service.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("Часть"))
                .expectNextMatches(e -> e.type().equals("failed") && !e.message().contains("private-provider-data"))
                .verifyComplete();
    }

    @Test
    void cancelsUpstreamWhenConsumerCancels() {
        var cancelled = new AtomicBoolean();
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(part("Часть", "")), Flux.<ChatResponse>never()).doOnCancel(() -> cancelled.set(true)));
        StepVerifier.create(this.service.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("Часть")).thenCancel().verify();
        assertThat(cancelled).isTrue();
    }

    @Test
    void idleTimeoutTerminatesSilentModel() {
        when(this.model.stream(any(Prompt.class))).thenReturn(Flux.never());
        StepVerifier.withVirtualTime(() -> this.service.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started()).thenAwait(Duration.ofSeconds(31))
                .expectNextMatches(e -> e.code().equals("TIMEOUT")).verifyComplete();
    }

    @Test
    void totalDeadlineStopsAnEndlessButActiveModel() {
        var limited = new ChatStreamService(ChatClient.builder(this.model), "System",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        when(this.model.stream(any(Prompt.class))).thenAnswer(ignored ->
                Flux.interval(Duration.ofSeconds(1)).map(i -> part("x", "")));
        var deltas = new AtomicInteger();
        StepVerifier.withVirtualTime(() -> limited.stream("Вопрос"))
                .expectNext(ChatStreamEvent.started()).thenAwait(Duration.ofSeconds(6))
                .thenConsumeWhile(e -> e.type().equals("delta"), e -> deltas.incrementAndGet())
                .expectNextMatches(e -> e.code().equals("TIMEOUT")).verifyComplete();
        assertThat(deltas.get()).isBetween(3, 5);
    }

    @Test
    void subscriptionsDoNotShareCompletionState() {
        when(this.model.stream(any(Prompt.class))).thenReturn(
                Flux.just(part("Ответ", "stop")), Flux.empty());
        var events = this.service.stream("Вопрос");
        StepVerifier.create(events).expectNextCount(3).verifyComplete();
        StepVerifier.create(events).expectNext(ChatStreamEvent.started())
                .expectNextMatches(e -> e.code().equals("UPSTREAM_INCOMPLETE")).verifyComplete();
    }

    static ChatResponse part(String text, String reason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }
}
