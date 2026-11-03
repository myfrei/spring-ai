package dev.myfrei.repopilot.rankingeval;

public record RankingScore(Status status, int k, int evaluatedSlots, int uniqueRetrievedDocuments,
        int retrievedGoldDocuments, int totalGoldDocuments, Double dcg, Double idcg, Double ndcg, Double rr, Double recall) {
    public enum Status { SCORED, NO_GOLD }
}
