package dev.myfrei.repopilot.homework;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.ingestion.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class IncrementalReuseHomeworkAcceptanceTest {
    private final EmbeddingIdentity identity=new EmbeddingIdentity("model","a".repeat(64),3,"title-text-v1");
    private final ChunkingOptions options=new ChunkingOptions(ChunkingOptions.Strategy.MARKDOWN_SECTIONS,300,40);
    private Chunk chunk(String id,String version,String title,String section,String text) { return new Chunk(id,"doc","doc.md",title,section,0,1,2,"document-hash",Hashes.sha256(text),version,"policy",text); }
    private IndexArtifact artifact(List<EmbeddedChunk> entries,EmbeddingIdentity profile) { return new IndexArtifact(1,"old-fingerprint","old",options,profile,entries); }
    private EmbeddedChunk embedded(Chunk chunk) { return new EmbeddedChunk(chunk,List.of(1f,0f,0f)); }
    @Test void reusesSameEncoderInputAcrossNewIdsAndCorpusVersionsWithCurrentMetadata() {
        var old=chunk("old-id","old","Title","Section","same text");var current=chunk("new-id","new","Title","Section","same text");
        var plan=new IncrementalEmbeddingPlanner().plan(List.of(current),identity,Optional.of(artifact(List.of(embedded(old)),identity)));
        assertThat(plan.missing()).isEmpty();assertThat(plan.reusable()).hasSize(1);assertThat(plan.reusable().getFirst().chunk()).isEqualTo(current);assertThat(plan.reusable().getFirst().vector()).containsExactly(1f,0f,0f);
    }
    @Test void titleSectionTextAndProfileArePartOfReuseIdentity() {
        var old=chunk("old","old","Title","Section","text");var previous=Optional.of(artifact(List.of(embedded(old)),identity));var planner=new IncrementalEmbeddingPlanner();
        for(var current:List.of(chunk("a","new","Other","Section","text"),chunk("b","new","Title","Other","text"),chunk("c","new","Title","Section","different"))) assertThat(planner.plan(List.of(current),identity,previous).missing()).containsExactly(current);
        var changed=new EmbeddingIdentity("model","b".repeat(64),3,"title-text-v1");assertThat(planner.plan(List.of(old),changed,previous).reusable()).isEmpty();
        var format=new EmbeddingIdentity("model",identity.digest(),3,"new-input-format");assertThat(planner.plan(List.of(old),format,previous).missing()).containsExactly(old);
    }
    @Test void staleChunksNeverEnterNewPlanAndMissingOrderMatchesCurrentOrder() {
        var stale=chunk("stale","old","T","S","deleted");var unchanged=chunk("u","new","T","S","same");var first=chunk("a","new","T","S","new A");var second=chunk("b","new","T","S","new B");
        var plan=new IncrementalEmbeddingPlanner().plan(List.of(first,unchanged,second),identity,Optional.of(artifact(List.of(embedded(stale),embedded(unchanged)),identity)));
        assertThat(plan.reusable()).extracting(entry -> entry.chunk().id()).containsExactly("u");assertThat(plan.missing()).containsExactly(first,second);
        assertThatThrownBy(() -> new IncrementalEmbeddingPlanner().plan(List.of(first,first),identity,Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidCachedVectorIsNotReused() {
        var current=chunk("u","new","T","S","same");var invalid=new EmbeddedChunk(current,List.of(0f,0f,0f));
        assertThat(new IncrementalEmbeddingPlanner().plan(List.of(current),identity,Optional.of(artifact(List.of(invalid),identity))).missing()).containsExactly(current);
    }
    @Test void orchestrationEmbedsOnlyMissingAndPublishesWholeCurrentSet() {
        var corpus=mock(CorpusCatalog.class);var chunker=mock(MarkdownChunker.class);var gateway=mock(OllamaEmbeddingGateway.class);var store=mock(IndexArtifactStore.class);
        var reused=chunk("u","new","T","S","same");var missing=chunk("m","new","T","S","new");var stale=chunk("s","old","T","S","gone");var snapshot=new CorpusSnapshot("new",List.of());
        when(corpus.snapshot()).thenReturn(snapshot);when(chunker.split(snapshot,options)).thenReturn(List.of(missing,reused));when(gateway.identity()).thenReturn(identity);when(store.load()).thenReturn(Optional.of(artifact(List.of(embedded(reused),embedded(stale)),identity)));when(gateway.embed(List.of(missing))).thenReturn(List.<float[]>of(new float[]{0,1,0}));
        var service=new IngestionService(corpus,chunker,gateway,new VectorValidator(),store,8);var result=service.incrementalIndex(options,new IncrementalEmbeddingPlanner());
        assertThat(result.reusedVectors()).isEqualTo(1);assertThat(result.embeddedVectors()).isEqualTo(1);verify(gateway).embed(List.of(missing));
        var saved=ArgumentCaptor.forClass(IndexArtifact.class);verify(store).save(saved.capture());assertThat(saved.getValue().chunks()).extracting(entry -> entry.chunk().id()).containsExactly("m","u");
    }
    @Test void invalidNewVectorDoesNotPublishPartialReuse() {
        var corpus=mock(CorpusCatalog.class);var chunker=mock(MarkdownChunker.class);var gateway=mock(OllamaEmbeddingGateway.class);var store=mock(IndexArtifactStore.class);var snapshot=new CorpusSnapshot("new",List.of());var current=chunk("m","new","T","S","new");
        when(corpus.snapshot()).thenReturn(snapshot);when(chunker.split(snapshot,options)).thenReturn(List.of(current));when(gateway.identity()).thenReturn(identity);when(store.load()).thenReturn(Optional.empty());when(gateway.embed(List.of(current))).thenReturn(List.<float[]>of(new float[]{0,0,0}));
        assertThatThrownBy(() -> new IngestionService(corpus,chunker,gateway,new VectorValidator(),store,8).incrementalIndex(options,new IncrementalEmbeddingPlanner())).isInstanceOf(IngestionProblem.class);
        verify(store,never()).save(any());
    }
}
