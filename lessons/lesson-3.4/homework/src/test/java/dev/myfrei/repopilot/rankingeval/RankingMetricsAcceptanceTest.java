package dev.myfrei.repopilot.rankingeval;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class RankingMetricsAcceptanceTest {
    private final RankingMetrics metrics = new RankingMetrics();
    private RankingScore score(List<String> ids, Map<String, Integer> grades, int k) { return this.metrics.evaluate(new RankingObservation(ids, grades, k)); }
    private static double discount(int rank) { return Math.log(rank + 1) / Math.log(2); }

    @Test void perfectGradedUniqueRankingReachesOneWithExactExponentialGain() {
        var result = score(List.of("a", "b", "c"), Map.of("a", 3, "b", 2, "c", 1), 3);
        double ideal = 7 + 3 / discount(2) + 1 / discount(3);
        assertThat(result.status()).isEqualTo(RankingScore.Status.SCORED);
        assertThat(result.dcg()).isCloseTo(ideal, within(1e-12)); assertThat(result.idcg()).isCloseTo(ideal, within(1e-12));
        assertThat(result.ndcg()).isEqualTo(1.0); assertThat(result.rr()).isEqualTo(1.0); assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.evaluatedSlots()).isEqualTo(3); assertThat(result.uniqueRetrievedDocuments()).isEqualTo(3);
        assertThat(result.retrievedGoldDocuments()).isEqualTo(3); assertThat(result.totalGoldDocuments()).isEqualTo(3);
        var cutoff = score(List.of("a"), Map.of("a", 3, "b", 2, "c", 1), 1);
        assertThat(cutoff.idcg()).isEqualTo(7.0); assertThat(cutoff.ndcg()).isEqualTo(1.0); assertThat(cutoff.rr()).isEqualTo(1.0);
        assertThat(cutoff.recall()).isCloseTo(1.0 / 3, within(1e-12)); assertThat(cutoff.totalGoldDocuments()).isEqualTo(3);
    }

    @Test void gradedOrderChangesNdcgWhileRecallAndRrCanStayPerfect() {
        var result = score(List.of("c", "b", "a"), Map.of("a", 3, "b", 2, "c", 1), 3);
        double actual = 1 + 3 / discount(2) + 7 / discount(3), ideal = 7 + 3 / discount(2) + 1 / discount(3);
        assertThat(result.dcg()).isCloseTo(actual, within(1e-12)); assertThat(result.idcg()).isCloseTo(ideal, within(1e-12));
        assertThat(result.ndcg()).isCloseTo(actual / ideal, within(1e-12)).isBetween(0.0, 1.0);
        assertThat(result.rr()).isEqualTo(1.0); assertThat(result.recall()).isEqualTo(1.0);
    }

    @Test void duplicateChunksConsumeSlotsButOnlyFirstOccurrenceEarnsGainAndRecall() {
        var result = score(List.of("a", "a", "b", "c"), Map.of("a", 3, "b", 2, "c", 1), 3);
        double actual = 7 + 3 / discount(3), ideal = 7 + 3 / discount(2) + 1 / discount(3);
        assertThat(result.dcg()).isCloseTo(actual, within(1e-12)); assertThat(result.ndcg()).isCloseTo(actual / ideal, within(1e-12)).isBetween(0.0, 1.0);
        assertThat(result.rr()).isEqualTo(1.0); assertThat(result.recall()).isCloseTo(2.0 / 3, within(1e-12));
        assertThat(result.evaluatedSlots()).isEqualTo(3); assertThat(result.uniqueRetrievedDocuments()).isEqualTo(2); assertThat(result.retrievedGoldDocuments()).isEqualTo(2);
    }

    @Test void duplicatesNeverRefillCutoffAndZeroGradeDoesNotBecomeGold() {
        var result = score(List.of("a", "a", "a", "gold"), Map.of("a", 0, "gold", 3), 3);
        assertThat(result.totalGoldDocuments()).isEqualTo(1); assertThat(result.retrievedGoldDocuments()).isZero(); assertThat(result.uniqueRetrievedDocuments()).isEqualTo(1);
        assertThat(result.dcg()).isZero(); assertThat(result.idcg()).isEqualTo(7.0); assertThat(result.ndcg()).isZero(); assertThat(result.rr()).isZero(); assertThat(result.recall()).isZero();
    }

    @Test void rrUsesOriginalChunkRankAndCountsOnlyPositiveRelevanceInsideK() {
        var grades = Map.of("zero", 0, "a", 1, "b", 3);
        var result = score(List.of("unjudged", "zero", "b", "a"), grades, 4);
        assertThat(result.rr()).isCloseTo(1.0 / 3, within(1e-12)); assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.dcg()).isCloseTo(7 / discount(3) + 1 / discount(4), within(1e-12));
        var outside = score(List.of("unjudged", "zero", "b", "a"), grades, 2);
        assertThat(outside.rr()).isZero(); assertThat(outside.recall()).isZero(); assertThat(outside.ndcg()).isZero();
    }

    @Test void emptyRetrievalWithGoldScoresZeroButNoPositiveGoldMakesAllMetricsNull() {
        var empty = score(List.of(), Map.of("gold", 3), 20);
        assertThat(empty.status()).isEqualTo(RankingScore.Status.SCORED); assertThat(empty.dcg()).isZero(); assertThat(empty.idcg()).isEqualTo(7.0);
        assertThat(empty.ndcg()).isZero(); assertThat(empty.rr()).isZero(); assertThat(empty.recall()).isZero(); assertThat(empty.evaluatedSlots()).isZero();
        for (var grades : List.of(Map.<String, Integer>of(), Map.of("a", 0))) {
            var noGold = score(List.of("a", "a", "other"), grades, 2);
            assertThat(noGold.status()).isEqualTo(RankingScore.Status.NO_GOLD);
            assertThat(noGold.dcg()).isNull(); assertThat(noGold.idcg()).isNull(); assertThat(noGold.ndcg()).isNull(); assertThat(noGold.rr()).isNull(); assertThat(noGold.recall()).isNull();
            assertThat(noGold.evaluatedSlots()).isEqualTo(2); assertThat(noGold.uniqueRetrievedDocuments()).isEqualTo(1); assertThat(noGold.totalGoldDocuments()).isZero();
        }
    }

    @Test void evaluationUsesImmutableSnapshotWithoutReorderingInputAndRejectsInvalidBounds() {
        var ids = new ArrayList<>(List.of("b", "a")); var grades = new LinkedHashMap<String, Integer>(); grades.put("a", 3); grades.put("b", 1);
        var observation = new RankingObservation(ids, grades, 2); ids.clear(); grades.clear();
        var result = this.metrics.evaluate(observation);
        assertThat(observation.rankedDocumentIds()).containsExactly("b", "a"); assertThat(observation.relevanceByDocument()).containsExactlyInAnyOrderEntriesOf(Map.of("a", 3, "b", 1));
        assertThat(result.ndcg()).isBetween(0.0, 1.0);
        assertThatThrownBy(() -> observation.rankedDocumentIds().add("mutate")).isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(List.of("a"), Map.of("a", 4), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(List.of("a"), Map.of("a", -1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(List.of("private/path.md"), Map.of(), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(Collections.nCopies(101, "a"), Map.of(), 1));
        var tooMany = new HashMap<String, Integer>(); for (int i = 0; i < 101; i++) tooMany.put("doc-" + i, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(List.of(), tooMany, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(List.of(), Map.of(), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RankingObservation(List.of(), Map.of(), 21));
    }

    @Test void standaloneHttpApiNeedsNoSqlOrModelAndReturnsSanitized400ForInvalidBody() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new RankingEvaluationController(this.metrics)).setControllerAdvice(new RankingEvaluationExceptionHandler()).build();
        var bad = http.perform(post("/api/ranking/evaluate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rankedDocumentIds\":[\"RAW_PRIVATE_ANSWER_DO_NOT_ECHO\"],\"relevanceByDocument\":{},\"k\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANKING_OBSERVATION")).andReturn();
        assertThat(bad.getResponse().getContentAsString()).doesNotContain("RAW_PRIVATE_ANSWER_DO_NOT_ECHO");
        http.perform(post("/api/ranking/evaluate").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANKING_OBSERVATION"));
        for (String invalid : List.of(
                "{\"rankedDocumentIds\":[\"a\"],\"relevanceByDocument\":{\"a\":3.5},\"k\":1}",
                "{\"rankedDocumentIds\":[\"a\"],\"relevanceByDocument\":{\"a\":3},\"k\":1.5}",
                "{\"rankedDocumentIds\":[\"a\"],\"relevanceByDocument\":{\"a\":3},\"k\":\"1\"}",
                "{\"rankedDocumentIds\":[\"a\"],\"relevanceByDocument\":{\"a\":\"3\"},\"k\":1}",
                "{\"rankedDocumentIds\":[\"a\"],\"relevanceByDocument\":{\"a\":null},\"k\":1}",
                "{\"rankedDocumentIds\":[\"a\"],\"relevanceByDocument\":{\"a\":3},\"k\":1,\"answer\":\"RAW_PRIVATE_ANSWER_DO_NOT_ECHO\"}")) {
            var response = http.perform(post("/api/ranking/evaluate").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANKING_OBSERVATION")).andReturn();
            assertThat(response.getResponse().getContentAsString()).doesNotContain("RAW_PRIVATE_ANSWER_DO_NOT_ECHO");
        }
        http.perform(post("/api/ranking/evaluate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rankedDocumentIds\":[\"overview\"],\"relevanceByDocument\":{\"overview\":3},\"k\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCORED"))
                .andExpect(jsonPath("$.ndcg").value(1.0)).andExpect(jsonPath("$.recall").value(1.0));
        http.perform(post("/api/ranking/evaluate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rankedDocumentIds\":[\"overview\"],\"relevanceByDocument\":{},\"k\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_GOLD"))
                .andExpect(jsonPath("$.dcg").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.idcg").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.ndcg").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.rr").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recall").value(org.hamcrest.Matchers.nullValue()));
    }
}
