package dev.myfrei.repopilot.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.myfrei.repopilot.rag.RagManifest;

public record Experiment(UUID id, String startedAt, String finishedAt, State state, Request request,
        Conditions conditions, int planned, List<Result> warmups, List<Result> results,
        Map<Integer, Judgment> judgments, String message) {
    public Experiment { warmups = List.copyOf(warmups); results = List.copyOf(results); judgments = Map.copyOf(judgments); }
    public enum Mode { CHAT, FULL_CONTEXT, RAG }
    public enum Split { DEV, HOLDOUT }
    public enum State { RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }
    public enum Outcome { COMPLETED, NO_EVIDENCE, FAILED, CANCELLED }
    public record Request(Split split, List<String> caseIds, List<Mode> modes, Integer repeats,
            Integer topK, Double threshold, Boolean warmup) {
        public Request {
            if (split == null) split = Split.DEV;
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            modes = modes == null ? List.of(Mode.CHAT, Mode.FULL_CONTEXT, Mode.RAG) : List.copyOf(modes);
            if (repeats == null) repeats = 1; if (topK == null) topK = 4;
            if (threshold == null) threshold = .35; if (warmup == null) warmup = true;
            if (modes.isEmpty() || modes.size() > 3 || modes.stream().distinct().count() != modes.size()
                    || caseIds.size() > 30 || caseIds.stream().distinct().count() != caseIds.size()
                    || repeats < 1 || repeats > 3 || topK < 1 || topK > 8
                    || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("Invalid experiment request");
        }
    }
    public record ChatProfile(String model, String digest, double temperature, int contextWindow, int outputLimit, String systemPromptHash) { }
    public record Conditions(String datasetVersion, String corpusVersion, ChatProfile chat, RagManifest rag,
            String estimator, int inputBudget, String pipelineVersion, String cacheEvidence, String orderPolicy) { }
    public record Source(String label, String documentId, String path, String chunkId, String text) { }
    public record Timing(double totalMs, double preparationMs, double retrievalMs, Double generationMs,
            Double endToEndTtftMs, Double modelTtftMs) { }
    public record Result(int ordinal, String caseId, int repetition, Mode mode, String question, Outcome outcome,
            String answer, String code, Timing timing, Integer estimatedInputTokens, Integer providerInputTokens,
            Integer providerOutputTokens, int retrievedChunks, List<Source> sources, Boolean retrievedEvidenceHit,
            Boolean providedEvidenceHit, String promptHash) {
        public Result { sources = List.copyOf(sources); }
    }
    public record Judgment(int answer, int citations, Boolean refusalCorrect, String notes, String reviewedAt) {
        public Judgment {
            if (answer < 0 || answer > 2 || citations < 0 || citations > 2 || notes == null || notes.length() > 2000) throw new IllegalArgumentException("Invalid manual judgment");
        }
    }
}
