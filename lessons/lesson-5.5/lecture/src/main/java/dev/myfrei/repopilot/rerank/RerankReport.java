package dev.myfrei.repopilot.rerank;

import java.util.List;

/** Public metadata only. Raw candidate text and model output are never part of this DTO. */
public record RerankReport(RerankRequest.Strategy requestedStrategy,RerankRequest.Strategy effectiveStrategy,
        int candidateCount,int keptBeforeBudget,List<Rank> ranking,int inferenceAttempts,double elapsedMs,
        Integer providerInputTokens,Integer providerOutputTokens,Integer estimatedInputTokens,int inputBudget,
        String modelDigest,String algorithmVersion,List<String> notes) {
    public RerankReport { ranking=List.copyOf(ranking); notes=List.copyOf(notes); }
    public record Rank(String chunkId,int originalRank,int rerankRank,double cosine,Double score,boolean kept) { }
}
