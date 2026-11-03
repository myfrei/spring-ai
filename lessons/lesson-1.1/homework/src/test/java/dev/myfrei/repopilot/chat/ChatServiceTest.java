package dev.myfrei.repopilot.chat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ChatServiceTest {

    private ChatModel model;
    private ChatService service;

    @BeforeEach
    void setUp() {
        this.model = mock(ChatModel.class);
        when(this.model.getOptions()).thenReturn(ChatOptions.builder().model("unit-test-model").build());
        this.service = new ChatService(ChatClient.builder(this.model));
        clearInvocations(this.model);
    }

    @Test
    void sendsTrimmedUserMessageAndReturnsModelAnswer() {
        replyWith("Record хранит данные.");

        assertThat(this.service.ask("  Что такое record?  ")).isEqualTo("Record хранит данные.");

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(this.model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("Что такое record?");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsBlankInputWithoutCallingModel(String message) {
        assertThatThrownBy(() -> this.service.ask(message))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.model);
    }

    @Test
    void rejectsMoreThan4000CharactersWithoutCallingModel() {
        assertThatThrownBy(() -> this.service.ask("a".repeat(4_001)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(this.model);
    }

    @Test
    void acceptsExactly4000Characters() {
        replyWith("Ответ");
        assertThat(this.service.ask("a".repeat(4_000))).isEqualTo("Ответ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void refusesToReturnAnEmptyModelAnswer(String answer) {
        replyWith(answer);
        assertThatThrownBy(() -> this.service.ask("Вопрос"))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void translatesProviderFailuresWithoutExposingTheirDetails(RuntimeException failure) {
        when(this.model.call(any(Prompt.class))).thenThrow(failure);

        assertThatThrownBy(() -> this.service.ask("Вопрос"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasCause(failure)
                .hasMessageNotContaining("internal-provider-detail");
    }

    @Test
    void doesNotDisguiseProgrammingErrorsAsUnavailableModel() {
        when(this.model.call(any(Prompt.class))).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> this.service.ask("Вопрос"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bug");
    }

    private void replyWith(String text) {
        when(this.model.call(any(Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage(text)))));
    }

    static Stream<RuntimeException> providerFailures() {
        return Stream.of(
                new ResourceAccessException("internal-provider-detail"),
                new TransientAiException("internal-provider-detail"),
                new NonTransientAiException("internal-provider-detail"));
    }
}
