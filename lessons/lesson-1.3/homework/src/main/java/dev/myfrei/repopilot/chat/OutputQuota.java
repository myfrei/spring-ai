package dev.myfrei.repopilot.chat;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
@Component
public class OutputQuota {
    public Flux<ChatStreamEvent> apply(Flux<ChatStreamEvent> source, int maxCodePoints) {
        // TODO HW-3: бюджет каждой подписки; префикс delta, failed OUTPUT_BUDGET и отмена upstream.
        throw new UnsupportedOperationException("TODO HW-3: output quota");
    }
}
