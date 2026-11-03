package dev.myfrei.repopilot.diagnostics;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.*;
import static org.assertj.core.api.Assertions.*;
class DiagnosticLabAndServiceTest {
    @TempDir Path directory;
    @Test void labUsesRealBudgetSelectionAndExplicitSyntheticGeneration() {
        var store = new TraceStore(directory.resolve("traces").toString());
        var report = new DiagnosticLab(DiagnosticFixtures.corpus(), DiagnosticFixtures.dataset(), new TraceRecorder(store)).replay();
        assertThat(report.origin()).isEqualTo("SYNTHETIC_LAB");
        assertThat(report.rows()).extracting(row -> row.finding().category()).containsExactly(Category.RETRIEVAL_MISS, Category.CONTEXT_DROPPED, Category.GENERATION_REVIEW);
        var budget = store.load(UUID.fromString(report.rows().get(1).traceId()));
        assertThat(budget.sources()).hasSize(2); assertThat(budget.sources()).filteredOn(SourceMark::selected).hasSize(1);
        assertThat(budget.events()).anySatisfy(event -> { assertThat(event.stage()).isEqualTo(Stage.ASSEMBLY); assertThat(event.counts()).containsEntry("omitted", 1); });
        var generation = store.load(UUID.fromString(report.rows().get(2).traceId()));
        assertThat(generation.context().synthetic()).isTrue(); assertThat(generation.events()).anySatisfy(event -> { assertThat(event.stage()).isEqualTo(Stage.GENERATION); assertThat(event.status()).isEqualTo(Status.SKIPPED); });
    }
    @Test void legacyResultWithoutTraceRemainsReadableButNotInvented() throws Exception {
        var runs = new RunStore(directory.resolve("runs").toString()); var run = DiagnosticFixtures.run(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, true), true); runs.save(run);
        Path file = directory.resolve("runs/run-" + run.id() + ".json");
        Files.writeString(file, Files.readString(file).replace(",\"traceId\":\"\"", ""));
        assertThat(runs.load(run.id()).results().getFirst().traceId()).isEmpty();
        var report = service(runs).analyze(run.id()); assertThat(report.baselineCompatible()).isTrue();
        assertThat(report.rows().getFirst().traceAvailable()).isFalse(); assertThat(report.rows().getFirst().traceWarning()).isEqualTo("TRACE_NOT_RECORDED");
        assertThat(report.rows().getFirst().finding().category()).isEqualTo(Category.MANUAL_REVIEW_NEEDED);
    }
    @Test void incompatibleDatasetIsNotDiagnosedAsModelFailure() {
        var runs = new RunStore(directory.resolve("runs").toString()); var run = DiagnosticFixtures.run(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, true), false); runs.save(run);
        assertThat(service(runs).analyze(run.id()).rows().getFirst().finding().category()).isEqualTo(Category.INCOMPATIBLE_BASELINE);
    }
    @Test void markdownSeparatesFactsHypothesisExperimentAndUnconfirmedConclusion() {
        var report = new DiagnosticLab(DiagnosticFixtures.corpus(), DiagnosticFixtures.dataset(), new TraceRecorder(new TraceStore(directory.toString()))).replay();
        assertThat(DiagnosticMarkdown.render(report)).contains("Наблюдаемые факты", "Гипотеза:", "Проверочный эксперимент:", "заполнить после эксперимента", "SYNTHETIC_LAB");
    }
    @Test void traceFromDifferentRunIsNotClaimedAsEvidence() {
        var row = DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, true);
        var recorder = new TraceRecorder(new TraceStore(directory.resolve("traces").toString()));
        var session = recorder.begin(new Context(UUID.randomUUID(), row.caseId(), row.ordinal(), row.repetition(), row.mode(), false,
                DiagnosticFixtures.corpus().snapshot().version(), DiagnosticFixtures.dataset().version())); session.finish(Outcome.COMPLETED, "");
        var correlated = new Result(row.ordinal(), row.caseId(), row.repetition(), row.mode(), row.question(), row.outcome(), row.answer(), row.code(), row.timing(), row.estimatedInputTokens(),
                row.providerInputTokens(), row.providerOutputTokens(), row.retrievedChunks(), row.sources(), row.retrievedEvidenceHit(), row.providedEvidenceHit(), row.promptHash(), session.id());
        var runs = new RunStore(directory.resolve("runs").toString()); var run = DiagnosticFixtures.run(correlated, true); runs.save(run);
        var diagnosis = service(runs).analyze(run.id()).rows().getFirst(); assertThat(diagnosis.traceAvailable()).isFalse(); assertThat(diagnosis.traceWarning()).isEqualTo("TRACE_IDENTITY_MISMATCH");
    }
    private DiagnosticService service(RunStore runs) { return new DiagnosticService(runs, DiagnosticFixtures.dataset(), DiagnosticFixtures.corpus(), new TraceStore(directory.resolve("traces").toString())); }
}
