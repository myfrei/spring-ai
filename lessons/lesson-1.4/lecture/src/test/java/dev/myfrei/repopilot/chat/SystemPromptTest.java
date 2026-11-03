package dev.myfrei.repopilot.chat;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemPromptTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Ты помощник Java-разработчика. Отвечай по-русски.",
            "Проверь ограничения проекта перед ответом."
    })
    void usesConfiguredSystemInstructionSeparatelyFromUserMessage(String systemPrompt) {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().model("unit-test-model").build());
        when(model.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage("Ответ")))));
        var service = new ChatService(ChatClient.builder(model), systemPrompt);

        assertThat(service.ask("  Что такое record?  ")).isEqualTo("Ответ");

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText()).isEqualTo(systemPrompt);
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("Что такое record?");
        assertThat(prompt.getValue().getInstructions()).extracting(message -> message.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
    }
}
