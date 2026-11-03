package dev.myfrei.repopilot.chat;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ChatStreamService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatStreamService.class);

    private final ChatClient client;
    private final String systemPrompt;
    private final Duration idleTimeout;
    private final Duration maxDuration;

    public ChatStreamService(ChatClient.Builder builder,
            @Value("${repopilot.system-prompt}") String systemPrompt,
            @Value("${repopilot.stream.idle-timeout}") Duration idleTimeout,
            @Value("${repopilot.stream.max-duration}") Duration maxDuration) {
        this.client = builder.build();
        this.systemPrompt = systemPrompt;
        this.idleTimeout = idleTimeout;
        this.maxDuration = maxDuration;
    }

    public Flux<ChatStreamEvent> stream(String message) {
        return stream(message, null, null);
    }

    public Flux<ChatStreamEvent> stream(String message, ChatMemory memory, String conversationId) {
        if (!StringUtils.hasText(message) || message.length() > 4_000) {
            throw new IllegalArgumentException("Сообщение должно содержать от 1 до 4000 символов.");
        }
        return streamResponses(() -> {
            var prompt = this.client.prompt().system(this.systemPrompt).user(message.strip());
            if (memory != null) {
                prompt.advisors(MessageChatMemoryAdvisor.builder(memory).build())
                        .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId));
            }
            return prompt.stream().chatResponse();
        });
    }

    public Flux<ChatStreamEvent> stream(List<Message> messages) {
        List<Message> snapshot = List.copyOf(messages);
        if (snapshot.isEmpty()) throw new IllegalArgumentException("Prompt must not be empty");
        return streamResponses(() -> this.client.prompt().messages(snapshot).stream().chatResponse());
    }

    public Flux<ChatStreamEvent> stream(List<Message> messages, org.springframework.ai.chat.client.advisor.api.Advisor advisor) {
        var snapshot = List.copyOf(messages);
        return streamResponses(() -> this.client.prompt().messages(snapshot).advisors(advisor).stream().chatResponse());
    }

    private Flux<ChatStreamEvent> streamResponses(Supplier<Flux<ChatResponse>> request) {
        return Flux.defer(() -> {
            var hasText = new AtomicBoolean();
            var finishReason = new AtomicReference<>("");
            Flux<ChatStreamEvent> deltas = Flux.defer(request::get)
                    .timeout(this.idleTimeout)
                    .takeUntilOther(Mono.delay(this.maxDuration).flatMap(ignored ->
                            Mono.error(new TimeoutException("Stream deadline exceeded"))))
                    .takeUntil(response -> StringUtils.hasText(reason(response)))
                    .<ChatStreamEvent>handle((response, sink) -> {
                        if (StringUtils.hasText(reason(response))) {
                            finishReason.set(reason(response));
                        }
                        String text = response.getResult() == null ? null
                                : response.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            if (StringUtils.hasText(text)) hasText.set(true);
                            sink.next(ChatStreamEvent.delta(text));
                        }
                    });

            return Flux.concat(Flux.just(ChatStreamEvent.started()), deltas,
                            Mono.fromSupplier(() -> terminal(finishReason.get(), hasText.get())))
                    .onErrorResume(error -> {
                        LOG.warn("Streaming failed: {}", error.getClass().getSimpleName());
                        return Mono.just(error instanceof TimeoutException
                                ? ChatStreamEvent.failed("TIMEOUT", "Модель не успела завершить ответ. Попробуйте ещё раз.")
                                : ChatStreamEvent.failed("MODEL_ERROR", "Поток модели прерван. Проверьте Ollama и повторите запрос."));
                    });
        });
    }

    private static String reason(ChatResponse response) {
        return response.getResult() == null ? "" : response.getResult().getMetadata().getFinishReason();
    }

    private static ChatStreamEvent terminal(String reason, boolean hasText) {
        if ("length".equalsIgnoreCase(reason)) {
            return ChatStreamEvent.failed("OUTPUT_LIMIT", "Ответ достиг лимита длины и остался незавершённым.");
        }
        if (!"stop".equalsIgnoreCase(reason)) {
            return ChatStreamEvent.failed("UPSTREAM_INCOMPLETE", "Модель не подтвердила завершение ответа.");
        }
        return hasText ? ChatStreamEvent.completed()
                : ChatStreamEvent.failed("EMPTY_RESPONSE", "Модель вернула пустой ответ.");
    }
}
