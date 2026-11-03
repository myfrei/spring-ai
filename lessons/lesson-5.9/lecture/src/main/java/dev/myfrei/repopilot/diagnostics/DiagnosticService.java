package dev.myfrei.repopilot.diagnostics;

import java.util.*;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.*;
public class DiagnosticService {
    private final RunStore runs;
    private final EvaluationDataset dataset;
    private final CorpusCatalog corpus;
    private final TraceStore traces;
    public DiagnosticService(RunStore runs, EvaluationDataset dataset, CorpusCatalog corpus, TraceStore traces) { this.runs = runs; this.dataset = dataset; this.corpus = corpus; this.traces = traces; }
    public DiagnosticReport latest() { return analyze(this.runs.list().stream().findFirst().orElseThrow(() -> new EvaluationProblem("NOT_FOUND", "Сначала выполните эксперимент 2.4/3.1 или откройте тренажёр.")).id()); }
    public DiagnosticReport analyze(UUID id) {
        var run = this.runs.load(id);
        boolean compatible = run.conditions() != null && this.dataset.version().equals(run.conditions().datasetVersion())
                && this.corpus.snapshot().version().equals(run.conditions().corpusVersion());
        var rows = new ArrayList<Row>();
        for (var row : run.results()) {
            var entry = this.dataset.cases(run.request().split()).stream().filter(item -> item.id().equals(row.caseId())).findFirst().orElse(null);
            var finding = compatible && (entry == null || entry.question().equals(row.question())) ? DiagnosticRules.classify(row, entry, run.judgments().get(row.ordinal())) : DiagnosticRules.incompatible("Dataset/corpus текущего приложения и run не совпадают либо conditions отсутствуют.");
            boolean available = false; String warning = row.traceId().isEmpty() ? "TRACE_NOT_RECORDED" : "";
            if (!row.traceId().isEmpty()) try {
                var trace = this.traces.load(UUID.fromString(row.traceId()));
                available = trace.context().runId().equals(run.id()) && trace.context().ordinal() == row.ordinal() && trace.context().caseId().equals(row.caseId()) && trace.context().mode() == row.mode() && trace.context().repetition() == row.repetition()
                        && !trace.context().synthetic() && run.conditions() != null && trace.context().corpusVersion().equals(run.conditions().corpusVersion())
                        && trace.context().datasetVersion().equals(run.conditions().datasetVersion());
                if (!available) warning = "TRACE_IDENTITY_MISMATCH";
            } catch (RuntimeException error) { warning = "TRACE_UNAVAILABLE"; }
            rows.add(new Row(row.ordinal(), row.caseId(), row.mode(), row.outcome(), finding, row.traceId(), available, warning, "", ""));
        }
        return new DiagnosticReport(run.id(), "EXPERIMENT", DiagnosticRules.VERSION, compatible, rows,
                "Это правила диагностики по наблюдаемым признакам, а не автоматическое доказательство причины. Тексты доступны в исходном evaluation run; в trace их нет.");
    }
}
