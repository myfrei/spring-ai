package dev.myfrei.repopilot.diagnostics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.myfrei.repopilot.evaluation.Experiment.Mode;

public record DiagnosticTrace(UUID id, Context context, String startedAt, String finishedAt, String outcome,
        List<Event> events, List<SourceMark> sources) {
    public DiagnosticTrace {
        events = List.copyOf(events); sources = List.copyOf(sources);
        if (id == null || context == null || startedAt == null || finishedAt == null || events.size() > 64) throw new IllegalArgumentException("Invalid trace");
        dev.myfrei.repopilot.evaluation.Experiment.Outcome.valueOf(outcome);
        for (int i = 0; i < events.size(); i++) if (events.get(i).sequence() != i + 1) throw new IllegalArgumentException("Non-contiguous events");
    }
    public enum Stage { REQUEST, GUARD, RETRIEVAL, ASSEMBLY, GENERATION, FIRST_TEXT, VERIFY, FINISH }
    public enum Status { STARTED, COMPLETED, FAILED, SKIPPED, CANCELLED }
    public record Context(UUID runId, String caseId, int ordinal, int repetition, Mode mode,
            boolean synthetic, String corpusVersion, String datasetVersion) {
        public Context {
            if (runId == null || mode == null || caseId == null || !caseId.matches("[a-z0-9-]{1,40}")
                    || !hashOrEmpty(corpusVersion) || !hashOrEmpty(datasetVersion)) throw new IllegalArgumentException("Invalid trace context");
        }
    }
    public record Event(int sequence, Stage stage, Status status, double elapsedMs,
            Map<String, Integer> counts, Map<String, String> hashes, String code) {
        private static final java.util.Set<String> COUNTS = java.util.Set.of("requestChars", "retrieved", "selected", "omitted", "estimated", "inputBudget", "deltas", "answerChars", "providerInput", "providerOutput");
        private static final java.util.Set<String> HASHES = java.util.Set.of("promptHash", "indexFingerprint");
        public Event {
            counts = Map.copyOf(counts); hashes = Map.copyOf(hashes);
            if (sequence < 1 || stage == null || status == null || !Double.isFinite(elapsedMs) || elapsedMs < 0
                    || !COUNTS.containsAll(counts.keySet()) || counts.values().stream().anyMatch(value -> value < 0)
                    || !HASHES.containsAll(hashes.keySet()) || hashes.values().stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))
                    || code == null || !code.matches("[A-Z0-9_]{0,64}")) throw new IllegalArgumentException("Unsafe trace event");
        }
    }
    public record SourceMark(String documentId, String chunkId, String textHash, double score, boolean selected) {
        public SourceMark {
            if (!documentId.matches("[a-z0-9-]{1,40}") || !chunkId.matches("[0-9a-f]{64}") || !textHash.matches("[0-9a-f]{64}")
                    || !Double.isFinite(score)) throw new IllegalArgumentException("Invalid source marker");
        }
    }
    private static boolean hashOrEmpty(String value) { return value != null && (value.isEmpty() || value.matches("[0-9a-f]{64}")); }
}
