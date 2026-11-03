package dev.myfrei.repopilot.rankingeval;

/** Pure offline policy: no model, SQL, retrieval, prompt, timing or answer content. */
public class RankingMetrics {
    public RankingScore evaluate(RankingObservation observation) {
        // TODO HW-3.4: slot-preserving document-aware nDCG@K, RR@K and Recall@K.
        throw new UnsupportedOperationException("HW-3.4: evaluate offline ranking metadata");
    }
}
