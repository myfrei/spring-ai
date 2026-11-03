package dev.myfrei.repopilot.query;

import java.util.List;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.ingestion.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReciprocalRankFusionTest {
    private final RagManifest manifest=new RagManifest("taskshelf","a".repeat(64),"b".repeat(64),new EmbeddingIdentity("m","c".repeat(64),3,"v1"),2);
    private RagHit hit(String id,double cosine) {
        return new RagHit(new RagSource("S1",Hashes.sha256(id),"runbook","RUNBOOK.md","Runbook","Start",1,2,Hashes.sha256("text:"+id),cosine),"text:"+id);
    }
    private RagSearchResult results(RagHit... hits) { return new RagSearchResult(manifest,List.of(hits)); }
    @Test void sharedChunkWinsEvenWhenAnotherHasHigherCosine() {
        var merged=ReciprocalRankFusion.merge(List.of(results(hit("rare",.99),hit("shared",.8)),results(hit("shared",.7))),8);
        assertThat(merged.retrieval().hits().getFirst().source().chunkId()).isEqualTo(Hashes.sha256("shared"));
        assertThat(merged.ranks().getFirst().support()).isEqualTo(2);
        assertThat(merged.ranks().getFirst().fusionScore()).isCloseTo(1.0/62+1.0/61,within(.0000001));
        assertThat(merged.ranks().getFirst().bestCosine()).isEqualTo(.8);
        assertThat(merged.retrieval().hits()).extracting(h->h.source().label()).containsExactly("S1","S2");
    }
    @Test void duplicateWithinOneListDoesNotIncreaseSupport() {
        var merged=ReciprocalRankFusion.merge(List.of(results(hit("one",.9),hit("one",.9))),8);
        assertThat(merged.ranks()).hasSize(1); assertThat(merged.ranks().getFirst().support()).isOne();
    }
    @Test void tieOrderIsStableAndLimitIsAppliedAfterFusion() {
        var a=results(hit("a",.8)); var b=results(hit("b",.8));
        var first=ReciprocalRankFusion.merge(List.of(a,b),1); var second=ReciprocalRankFusion.merge(List.of(b,a),1);
        assertThat(first).isEqualTo(second); assertThat(first.ranks()).hasSize(1);
    }
    @Test void rejectsDifferentSnapshotsAndInconsistentChunkIdentity() {
        var other=new RagManifest("taskshelf","d".repeat(64),manifest.corpusVersion(),manifest.embedding(),2);
        assertThatThrownBy(()->ReciprocalRankFusion.merge(List.of(results(hit("a",.9)),new RagSearchResult(other,List.of())),8)).extracting("code").isEqualTo("INDEX_CHANGED");
        var same=hit("a",.9); var corrupt=new RagHit(same.source(),"tampered");
        assertThatThrownBy(()->ReciprocalRankFusion.merge(List.of(results(same),results(corrupt)),8)).extracting("code").isEqualTo("INDEX_CORRUPT");
    }
    @Test void emptyHitsAreValidButSearchFanoutAndLimitAreBounded() {
        assertThat(ReciprocalRankFusion.merge(List.of(results()),8).retrieval().hits()).isEmpty();
        assertThatThrownBy(()->ReciprocalRankFusion.merge(List.of(results(),results(),results(),results()),8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ReciprocalRankFusion.merge(List.of(results()),9)).isInstanceOf(IllegalArgumentException.class);
    }
}
