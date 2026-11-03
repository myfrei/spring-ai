package dev.myfrei.repopilot.homework;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.rag.*;
import static org.assertj.core.api.Assertions.*;
class EvidenceSelectionHomeworkAcceptanceTest {
    private RagHit hit(String id,String doc,int start,int end,double score) { return new RagHit(new RagSource("old-label",id,doc,doc+".md","Title","Section",start,end,"hash-"+id,score),"text-"+id); }
    @Test void removesOverlappingChunksBeforePerDocumentCapAndKeepsOtherDocuments() {
        var result=new EvidenceSelector().select(List.of(hit("a","one",1,10,.99),hit("b","one",6,12,.98),hit("c","one",30,40,.97),hit("d","two",1,10,.96)),2,8,.5);
        assertThat(result).extracting(item->item.source().chunkId()).containsExactly("a","c","d");
    }
    @Test void perDocumentCapPreventsOneDocumentFromOccupyingAllEvidence() {
        var result=new EvidenceSelector().select(List.of(hit("a","one",1,3,.99),hit("b","one",10,12,.98),hit("c","two",1,3,.97)),1,2,.5);
        assertThat(result).extracting(item->item.source().chunkId()).containsExactly("a","c");
    }
    @Test void exactDuplicatesAreRemovedAndSurvivorsHaveContiguousLabelsAndOriginalPayload() {
        var a=hit("a","one",1,3,.99);var b=hit("b","two",1,3,.97);var result=new EvidenceSelector().select(List.of(a,a,b),2,8,.5);
        assertThat(result).extracting(item->item.source().label()).containsExactly("S1","S2");
        assertThat(result.getLast().text()).isEqualTo(b.text());assertThat(result.getLast().source().textHash()).isEqualTo(b.source().textHash());assertThat(result.getLast().source().score()).isEqualTo(.97);
        assertThat(a.source().label()).isEqualTo("old-label");
    }
    @Test void inclusiveLineOverlapBoundaryAndThresholdZeroAreDefined() {
        var selector=new EvidenceSelector();var first=hit("a","one",1,10,.9);var boundary=hit("b","one",10,19,.8);var separate=hit("c","one",20,29,.7);
        assertThat(selector.select(List.of(first,boundary,separate),3,8,.1)).extracting(hit->hit.source().chunkId()).containsExactly("a","c");
        assertThat(selector.select(List.of(first,separate),3,8,0)).hasSize(2);
    }
    @Test void validatesPolicyAndSourceRangesInsteadOfSilentlyIgnoringBadInputs() {
        var selector=new EvidenceSelector();assertThatThrownBy(()->selector.select(List.of(),0,4,.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->selector.select(List.of(),1,9,.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->selector.select(List.of(),1,4,Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->selector.select(List.of(hit("x","doc",5,4,.9)),1,4,.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
