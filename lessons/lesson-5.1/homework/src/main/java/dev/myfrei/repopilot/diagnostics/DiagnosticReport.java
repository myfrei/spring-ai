package dev.myfrei.repopilot.diagnostics;

import java.util.List;
import java.util.UUID;
import dev.myfrei.repopilot.evaluation.Experiment.*;
public record DiagnosticReport(UUID runId, String origin, String rulesVersion, boolean baselineCompatible,
        List<Row> rows, String note) {
    public DiagnosticReport { rows = List.copyOf(rows); }
    public enum Category { INCOMPATIBLE_BASELINE, EXECUTION_FAILURE, BASELINE_WITHOUT_DOCUMENTS, RETRIEVAL_MISS,
        CONTEXT_DROPPED, INCONSISTENT_EVIDENCE, DATA_OR_CONTEXT_REVIEW, GENERATION_REVIEW, CITATION_REVIEW,
        UNSUPPORTED_ANSWER_REVIEW, MANUAL_REVIEW_NEEDED, NO_DETECTED_ISSUE }
    public record Finding(Category category, String label, List<String> facts, String hypothesis, String nextExperiment) {
        public Finding { facts = List.copyOf(facts); }
    }
    public record Row(int ordinal, String caseId, Mode mode, Outcome outcome, Finding finding, String traceId,
            boolean traceAvailable, String traceWarning, String sampleQuestion, String sampleAnswer) { }
}
