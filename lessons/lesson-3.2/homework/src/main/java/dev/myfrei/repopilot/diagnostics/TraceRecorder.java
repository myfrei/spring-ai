package dev.myfrei.repopilot.diagnostics;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.*;
public class TraceRecorder {
    private final TraceStore store;
    public TraceRecorder(TraceStore store) { this.store = store; }
    public boolean available(String id) { try { this.store.load(UUID.fromString(id)); return true; } catch (RuntimeException error) { return false; } }
    public TraceSession begin(Context context) {
        UUID id = UUID.randomUUID();
        var session = new TraceSession(id, context, System::nanoTime,
                event -> LoggerFactory.getLogger(TraceRecorder.class).info("trace_event {}", TraceLog.line(id, context, event)), this.store::save);
        session.event(Stage.REQUEST, Status.STARTED); return session;
    }
}
