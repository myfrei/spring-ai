package dev.myfrei.repopilot.explanation;
import dev.myfrei.repopilot.chat.LlmUnavailableException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
@Service
public class ExplanationService {
    private final ChatClient client;
    private final ExplanationPolicy policy;
    public ExplanationService(ChatClient.Builder builder, ExplanationPolicy policy) { this.client = builder.build(); this.policy = policy; }
    public String explain(ExplanationRequest request) {
        var messages = this.policy.messages(request.message(), request.mode());
        try {
            String answer = this.client.prompt().messages(messages).call().content();
            if (!StringUtils.hasText(answer)) throw new LlmUnavailableException();
            return answer;
        } catch (RestClientException | TransientAiException | NonTransientAiException error) { throw new LlmUnavailableException(error); }
    }
}
