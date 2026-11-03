package dev.myfrei.repopilot.rankingeval;

import java.util.List;
import java.util.Map;

/** Metadata-only observation: each ranked ID is one chunk slot, possibly repeating a document. */
public record RankingObservation(List<String> rankedDocumentIds, Map<String, Integer> relevanceByDocument, int k) {
    public RankingObservation {
        if (rankedDocumentIds == null || relevanceByDocument == null || rankedDocumentIds.size() > 100
                || relevanceByDocument.size() > 100 || k < 1 || k > 20) throw invalid();
        if (rankedDocumentIds.stream().anyMatch(id -> !validId(id))) throw invalid();
        for (var entry : relevanceByDocument.entrySet()) {
            if (!validId(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 3) throw invalid();
        }
        rankedDocumentIds = List.copyOf(rankedDocumentIds);
        relevanceByDocument = Map.copyOf(relevanceByDocument);
    }
    private static boolean validId(String id) { return id != null && id.matches("[a-z0-9-]{1,40}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid bounded ranking metadata"); }
}
