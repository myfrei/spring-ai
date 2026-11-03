package dev.myfrei.repopilot.diagnostics;

import java.time.Instant;
import java.util.*;
import java.util.function.*;
import dev.myfrei.repopilot.evaluation.Experiment.Outcome;
import dev.myfrei.repopilot.rag.RagHit;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.*;

public class TraceSession {
    private final UUID id;
    private final Context context;
    private final LongSupplier clock;
    private final Consumer<Event> logger;
    private final Consumer<DiagnosticTrace> writer;
    private final long start;
    private final String startedAt = Instant.now().toString();
    private final List<Event> events = new ArrayList<>();
    private List<RagHit> retrieved = List.of();
    private List<SourceMark> sources = List.of();
    private boolean closed;
    public static TraceSession disabled() { return new TraceSession(null, null, System::nanoTime, event -> { }, trace -> { }); }
    public TraceSession(UUID id, Context context, LongSupplier clock, Consumer<Event> logger, Consumer<DiagnosticTrace> writer) {
        this.id = id; this.context = context; this.clock = clock; this.logger = logger; this.writer = writer; this.start = clock.getAsLong();
    }
    public String id() { return this.id == null ? "" : this.id.toString(); }
    public synchronized void event(Stage stage, Status status, Map<String, Integer> counts, Map<String, String> hashes, String code) {
        if (this.id == null || this.closed || (this.events.size() >= 63 && stage != Stage.FINISH)) return;
        var event = new Event(this.events.size() + 1, stage, status, Math.max(0, this.clock.getAsLong() - this.start) / 1_000_000.0,
                counts, hashes, safeCode(code)); this.events.add(event);
        try { this.logger.accept(event); } catch (RuntimeException ignored) { /* Telemetry must not fail a request. */ }
    }
    public void event(Stage stage, Status status) { event(stage, status, Map.of(), Map.of(), ""); }
    public synchronized void retrieved(List<RagHit> hits) {
        if (this.id == null || this.closed) return;
        try { this.sources = SourceMarks.from(hits, List.of()); this.retrieved = List.copyOf(hits); }
        catch (RuntimeException ignored) { event(Stage.RETRIEVAL, Status.FAILED, Map.of(), Map.of(), "TRACE_METADATA_INVALID"); }
    }
    public synchronized void selected(List<String> ids) {
        if (this.id == null || this.closed) return;
        try { this.sources = SourceMarks.from(this.retrieved, ids); }
        catch (RuntimeException ignored) { event(Stage.ASSEMBLY, Status.FAILED, Map.of(), Map.of(), "TRACE_METADATA_INVALID"); }
    }
    public synchronized void finish(Outcome outcome, String code) {
        if (this.id == null || this.closed) return;
        event(Stage.FINISH, outcome == Outcome.FAILED ? Status.FAILED : outcome == Outcome.CANCELLED ? Status.CANCELLED : Status.COMPLETED,
                Map.of(), Map.of(), code); this.closed = true;
        var trace = new DiagnosticTrace(this.id, this.context, this.startedAt, Instant.now().toString(), outcome.name(), this.events, this.sources);
        try { this.writer.accept(trace); }
        catch (RuntimeException ignored) { org.slf4j.LoggerFactory.getLogger(TraceSession.class).warn("trace_save_failed traceId={}", this.id); }
        this.retrieved = List.of();
    }
    private static String safeCode(String code) { return code != null && code.matches("[A-Z0-9_]{0,64}") ? code : "REQUEST_ERROR"; }
}
