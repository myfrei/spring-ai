package dev.myfrei.repopilot.diagnostics;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.*;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class TraceContractTest {
    @TempDir Path directory;
    private Context context() { return new Context(UUID.randomUUID(), "dev-01", 0, 1, Mode.RAG, false, "a".repeat(64), "b".repeat(64)); }
    @Test void safeLogHasCorrelationAndWhitelistedMetadataOnly() {
        var context = context(); var event = new Event(1, Stage.ASSEMBLY, Status.COMPLETED, 12, Map.of("selected", 2), Map.of("promptHash", "c".repeat(64)), "");
        String line = TraceLog.line(UUID.randomUUID(), context, event);
        var tree = JsonMapper.builder().build().readTree(line);
        assertThat(tree.get("runId").asText()).isEqualTo(context.runId().toString()); assertThat(tree.get("event").get("counts").get("selected").asInt()).isEqualTo(2);
        assertThat(line).doesNotContain("question", "answer", "content", "path", "vector");
    }
    @Test void unsafeFreeFormFieldsAreRejected() {
        assertThatThrownBy(() -> new Event(1, Stage.REQUEST, Status.COMPLETED, 0, Map.of("prompt", 123), Map.of(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event(1, Stage.REQUEST, Status.COMPLETED, 0, Map.of(), Map.of("promptHash", "raw text"), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event(1, Stage.REQUEST, Status.COMPLETED, 0, Map.of(), Map.of(), "raw\nexception")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sourceMarkersKeepIdsHashesAndSelectionWithoutRawPayload() {
        String payload = "RAW_DOCUMENT_DO_NOT_LOG";
        var source = new RagSource("S1", "c".repeat(64), "runbook", "private/path.md", "Title", "Section", 1, 2, Hashes.sha256(payload), .9);
        var marks = SourceMarks.from(List.of(new RagHit(source, payload)), List.of(source.chunkId()));
        assertThat(marks.getFirst().selected()).isTrue(); assertThat(marks.getFirst().score()).isEqualTo(.9);
        assertThat(JsonMapper.builder().build().writeValueAsString(marks)).doesNotContain(payload, "private/path", "Title");
        assertThatThrownBy(() -> SourceMarks.from(List.of(new RagHit(source, payload)), List.of("d".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sessionHasOrderedBoundedEventsAndExactlyOneFinish() {
        var store = new TraceStore(directory.toString()); var clock = new AtomicLong(); UUID id = UUID.randomUUID(); var context = context(); var logs = new ArrayList<String>();
        var session = new TraceSession(id, context, clock::get, event -> logs.add(TraceLog.line(id, context, event)), store::save);
        for (int i = 0; i < 100; i++) { clock.addAndGet(1000000); session.event(Stage.GENERATION, Status.STARTED); }
        session.finish(Outcome.COMPLETED, ""); session.finish(Outcome.FAILED, "IGNORED");
        var trace = new TraceStore(directory.toString()).load(id);
        assertThat(trace.events()).hasSize(64); assertThat(trace.events().getLast().stage()).isEqualTo(Stage.FINISH);
        assertThat(trace.events()).extracting(Event::sequence).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 64).boxed().toList());
        assertThat(logs).hasSize(64); assertThat(trace.outcome()).isEqualTo("COMPLETED");
    }
    @Test void failingTelemetrySinkDoesNotFailBusinessLifecycle() {
        var session = new TraceSession(UUID.randomUUID(), context(), System::nanoTime, event -> { throw new IllegalStateException("sink"); }, trace -> { throw new IllegalStateException("disk"); });
        assertThatCode(() -> { session.event(Stage.GENERATION, Status.COMPLETED); session.finish(Outcome.COMPLETED, ""); }).doesNotThrowAnyException();
    }
}
