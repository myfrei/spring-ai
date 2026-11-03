package dev.myfrei.repopilot.context;

import java.util.List;

public record ContextReport(ContextMode mode, String corpusVersion, int estimatedInputTokens, int inputBudget,
        int outputReserve, int safetyMargin, int includedHistoryTurns, int omittedHistoryTurns,
        String estimator, String cacheEvidence, List<Source> sources) {
    public ContextReport { sources = List.copyOf(sources); }
    public record Source(String id, String path, String title, String sha256) { }
}
