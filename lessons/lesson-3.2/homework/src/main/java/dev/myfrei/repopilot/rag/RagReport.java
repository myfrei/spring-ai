package dev.myfrei.repopilot.rag;

import java.util.List;
import dev.myfrei.repopilot.ingestion.EmbeddingIdentity;

public record RagReport(String status, String workspace, String corpusVersion, String indexFingerprint, EmbeddingIdentity embedding,
        int estimatedInputTokens, int inputBudget, int includedHistoryTurns, int omittedHistoryTurns, int omittedChunks, List<RagSource> sources) {
    public RagReport { sources = List.copyOf(sources); }
}
