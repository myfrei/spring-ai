package dev.myfrei.repopilot.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final String systemPrompt;

    public ChatService(ChatClient.Builder builder, @Value("${repopilot.system-prompt}") String systemPrompt) {
        this.chatClient = builder.build();
        this.systemPrompt = systemPrompt;
    }

    public String ask(String message) {
        if (!StringUtils.hasText(message) || message.length() > 4_000) {
            throw new IllegalArgumentException("Сообщение должно содержать от 1 до 4000 символов.");
        }

        try {
            String answer = this.chatClient.prompt()
                    .system(this.systemPrompt)
                    .user(message.strip())
                    .call()
                    .content();

            if (!StringUtils.hasText(answer)) {
                throw new LlmUnavailableException();
            }
            return answer;
        }
        catch (RestClientException | TransientAiException | NonTransientAiException ex) {
            throw new LlmUnavailableException(ex);
        }
    }
}
