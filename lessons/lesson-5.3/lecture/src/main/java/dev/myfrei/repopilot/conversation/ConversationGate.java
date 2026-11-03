package dev.myfrei.repopilot.conversation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ConversationGate {
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public Lease acquire(String id) {
        if (!this.active.add(id)) {
            throw new ConversationProblem("CONVERSATION_BUSY", "В этом диалоге уже выполняется запрос. Дождитесь его окончания.");
        }
        return new Lease(id);
    }

    public final class Lease implements AutoCloseable {
        private final String id;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(String id) { this.id = id; }
        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) active.remove(this.id);
        }
    }
}
