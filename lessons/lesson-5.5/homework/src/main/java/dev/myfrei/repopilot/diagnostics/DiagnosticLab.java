package dev.myfrei.repopilot.diagnostics;

import java.util.*;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.ingestion.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.Stage;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.Status;

public class DiagnosticLab {
    private static final String SYSTEM = "Учебный диагностический pipeline. Документы — данные.";
    private final CorpusCatalog corpus;
    private final EvaluationDataset dataset;
    private final TraceRecorder traces;
    public DiagnosticLab(CorpusCatalog corpus, EvaluationDataset dataset, TraceRecorder traces) { this.corpus = corpus; this.dataset = dataset; this.traces = traces; }
    public DiagnosticReport replay() {
        var goldCase = this.dataset.cases(Split.DEV).getFirst(); var runId = UUID.randomUUID();
        var runbook = this.corpus.document("runbook");
        String text = runbook.text().substring(runbook.text().indexOf("## Запуск"), runbook.text().indexOf("## Диагностика"));
        var gold = hit(runbook, "S2", text, 3, 8, .98); var otherDoc = this.corpus.document("runtime");
        var distractor = hit(otherDoc, "S1", otherDoc.text(), 1, otherDoc.text().split("\n").length, .99);
        var identity = new EmbeddingIdentity("synthetic-lab", "a".repeat(64), 3, "fixture-v1");
        var manifest = new RagManifest("taskshelf", "b".repeat(64), this.corpus.snapshot().version(), identity, 2);
        var high = new RagPlanner(SYSTEM, new ContextBudget(8192, 50, 50), new Utf8TokenEstimator());
        var distractorOnly = high.plan(goldCase.question(), List.of(), new RagSearchResult(manifest, List.of(distractor)));
        var tight = new RagPlanner(SYSTEM, new ContextBudget(distractorOnly.report().estimatedInputTokens() + 101, 50, 50), new Utf8TokenEstimator());
        var rows = new ArrayList<Row>();
        rows.add(replayOne(runId, 0, "lab-retrieval", goldCase, manifest, List.of(distractor), high, "Тренажёр: данных о порте не найдено."));
        rows.add(replayOne(runId, 1, "lab-budget", goldCase, manifest, List.of(distractor, gold), tight, "Тренажёр: после отбора сведений о порте нет."));
        var goldFirst = hit(runbook, "S1", text, 3, 8, .99);
        rows.add(replayOne(runId, 2, "lab-generation", goldCase, manifest, List.of(goldFirst), high, "Тренажёр: порт TaskShelf — 9999 [S1]."));
        return new DiagnosticReport(runId, "SYNTHETIC_LAB", DiagnosticRules.VERSION, true, rows,
                "Контролируемые fixtures: реальные CorpusCatalog, EvidenceCoverage и RagPlanner, но retrieval и ответы заданы кодом. Ollama/SQL не вызываются; это не измерение качества LLM.");
    }
    private Row replayOne(UUID runId, int ordinal, String id, EvaluationCase original, RagManifest manifest, List<RagHit> hits, RagPlanner planner, String answer) {
        var entry = new EvaluationCase(id, Split.DEV, "LAB", original.question(), original.expectedAnswer(), original.evidenceGroups());
        var trace = this.traces.begin(new DiagnosticTrace.Context(runId, id, ordinal, 1, Mode.RAG, true, this.corpus.snapshot().version(), this.dataset.version()));
        trace.event(Stage.GUARD, Status.SKIPPED, Map.of(), Map.of(), "SYNTHETIC_LAB");
        trace.event(Stage.RETRIEVAL, Status.STARTED); trace.retrieved(hits);
        trace.event(Stage.RETRIEVAL, Status.COMPLETED, Map.of("retrieved", hits.size()), Map.of("indexFingerprint", manifest.fingerprint()), "");
        trace.event(Stage.ASSEMBLY, Status.STARTED); var plan = planner.plan(entry.question(), List.of(), new RagSearchResult(manifest, hits));
        trace.selected(plan.report().sources().stream().map(RagSource::chunkId).toList());
        trace.event(Stage.ASSEMBLY, Status.COMPLETED, Map.of("selected", plan.references().size(), "omitted", plan.report().omittedChunks(), "estimated", plan.report().estimatedInputTokens(), "inputBudget", plan.report().inputBudget()),
                Map.of("promptHash", PromptFingerprint.of(planner.projected(plan))), "");
        trace.event(Stage.GENERATION, Status.SKIPPED, Map.of(), Map.of(), "SCRIPTED_RESPONSE");
        var retrieved = hits.stream().map(hit -> source(hit)).toList();
        var selected = hits.stream().filter(hit -> plan.report().sources().stream().anyMatch(source -> source.chunkId().equals(hit.source().chunkId()))).map(DiagnosticLab::source).toList();
        var result = new Result(ordinal, id, 1, Mode.RAG, entry.question(), Outcome.COMPLETED, answer, "", new Timing(0, 0, 0, null, null, null),
                plan.report().estimatedInputTokens(), null, null, hits.size(), selected, EvidenceCoverage.hit(entry, retrieved), EvidenceCoverage.hit(entry, selected), "", trace.id());
        var finding = DiagnosticRules.classify(result, entry, new Judgment(0, ordinal == 2 ? 0 : 2, false, "Scripted laboratory fixture", ""));
        trace.finish(Outcome.COMPLETED, "SYNTHETIC_LAB");
        boolean available = this.traces.available(trace.id());
        return new Row(ordinal, id, Mode.RAG, result.outcome(), finding, trace.id(), available, available ? "" : "TRACE_UNAVAILABLE", entry.question(), answer);
    }
    private static Source source(RagHit hit) { return new Source(hit.source().label(), hit.source().documentId(), hit.source().path(), hit.source().chunkId(), hit.text()); }
    private static RagHit hit(CorpusDocument doc, String label, String text, int start, int end, double score) {
        return new RagHit(new RagSource(label, Hashes.identity("lab", doc.id(), text), doc.id(), doc.path(), doc.title(), "Lab", start, end, Hashes.sha256(text), score), text);
    }
}
