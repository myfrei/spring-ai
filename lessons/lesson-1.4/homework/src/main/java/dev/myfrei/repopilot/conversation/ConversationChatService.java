package dev.myfrei.repopilot.conversation;

import dev.myfrei.repopilot.chat.ChatStreamEvent;
import dev.myfrei.repopilot.chat.ChatStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class ConversationChatService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationChatService.class);
    private final SqliteHistory history;
    private final ConversationGate gate;
    private final ConversationMemoryFactory memories;
    private final ChatStreamService model;

    public ConversationChatService(SqliteHistory history, ConversationGate gate,
            ConversationMemoryFactory memories, ChatStreamService model) {
        this.history = history; this.gate = gate; this.memories = memories; this.model = model;
    }

    public Flux<ChatStreamEvent> stream(String id, String question) {
        if (!StringUtils.hasText(question) || question.length() > 4000) {
            throw new IllegalArgumentException("Question must contain 1..4000 characters");
        }
        Flux<ChatStreamEvent> work = Flux.using(() -> open(id, question), session ->
                this.model.stream(question, session.memory(), id)
                        .filter(event -> !event.type().equals("started"))
                        .publishOn(Schedulers.boundedElastic(), 1)
                        .map(event -> persist(session.turn(), event))
                        .doOnError(error -> this.history.finish(session.turn(), "failed", "HISTORY_ERROR")), this::close)
                .subscribeOn(Schedulers.boundedElastic())
                .cancelOn(Schedulers.boundedElastic());
        return Flux.concat(Flux.just(ChatStreamEvent.started()), work)
                .onErrorResume(error -> {
                    LOG.warn("Conversation stream failed: {}", error.getClass().getSimpleName());
                    return Flux.just(error instanceof ConversationProblem problem
                            ? ChatStreamEvent.failed(problem.code(), problem.getMessage())
                            : ChatStreamEvent.failed("HISTORY_ERROR", "Не удалось сохранить историю. Проверьте доступность SQLite."));
                });
    }

    private Session open(String id, String question) {
        var lease = this.gate.acquire(id);
        Long turn = null;
        try {
            turn = this.history.begin(id, question);
            return new Session(lease, turn, this.memories.create(id));
        } catch (RuntimeException ex) {
            try { if (turn != null) this.history.finish(turn, "failed", "HISTORY_ERROR"); }
            finally { lease.close(); }
            throw ex;
        }
    }

    private ChatStreamEvent persist(long turn, ChatStreamEvent event) {
        if (event.type().equals("delta")) this.history.append(turn, event.text());
        if (event.type().equals("completed")) this.history.finish(turn, "completed", "");
        if (event.type().equals("failed")) this.history.finish(turn, "failed", event.code());
        return event;
    }

    private void close(Session session) {
        try {
            // UPDATE ... WHERE pending не меняет уже сохранённый completed/failed.
            this.history.finish(session.turn(), "cancelled", "CLIENT_CANCELLED");
        } finally {
            session.lease().close();
        }
    }

    private record Session(ConversationGate.Lease lease, long turn, ChatMemory memory) { }
}
