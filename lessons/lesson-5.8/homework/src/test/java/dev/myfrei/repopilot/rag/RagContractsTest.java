package dev.myfrei.repopilot.rag;

import java.util.List;
import dev.myfrei.repopilot.ingestion.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.DefaultResourceLoader;
import dev.myfrei.repopilot.context.CorpusCatalog;
import static org.assertj.core.api.Assertions.*;

class RagContractsTest {
    private IndexArtifact artifact() {
        var corpus = new CorpusCatalog(new DefaultResourceLoader()).snapshot();
        var options = new ChunkingOptions(ChunkingOptions.Strategy.MARKDOWN_SECTIONS, 300, 40);
        var chunks = new MarkdownChunker().split(corpus, options);
        var identity = new EmbeddingIdentity("embeddinggemma:300m", "a".repeat(64), 3, OllamaEmbeddingGateway.INPUT_FORMAT);
        return new IndexArtifact(1, IndexArtifact.fingerprint(corpus.version(), options, identity), corpus.version(), options, identity,
                chunks.stream().map(chunk -> new EmbeddedChunk(chunk, List.of(1f, 0f, 0f))).toList());
    }
    @Test void mapsStableRowIdsAndSourceMetadataWithoutChangingText() {
        var artifact = artifact(); var entry = artifact.chunks().getFirst();
        var document = RagDocuments.document("taskshelf", artifact, entry);
        assertThat(document.getText()).isEqualTo(entry.chunk().text());
        assertThat(document.getId()).isEqualTo(RagDocuments.rowId("taskshelf", artifact.fingerprint(), entry.chunk().id()));
        assertThat(document.getMetadata()).containsEntry("chunkId", entry.chunk().id()).containsEntry("workspaceId", "taskshelf")
                .containsEntry("fingerprint", artifact.fingerprint()).containsEntry("embeddingDigest", artifact.embedding().digest())
                .containsEntry("textHash", entry.chunk().textHash()).containsEntry("startLine", entry.chunk().startLine());
        assertThat(RagDocuments.rowId("other", artifact.fingerprint(), entry.chunk().id())).isNotEqualTo(document.getId());
    }
    @Test void preparedAdapterReusesVectorsAndReturnsDefensiveCopies() {
        var artifact = artifact(); var document = RagDocuments.document("taskshelf", artifact, artifact.chunks().getFirst());
        var model = FixedEmbeddingModels.prepared("taskshelf", artifact);
        var first = model.embed(List.of(document), null, null); first.getFirst()[0] = 99;
        assertThat(model.embed(List.of(document), null, null).getFirst()).containsExactly(1, 0, 0);
        assertThat(model.dimensions()).isEqualTo(3);
    }
    @Test void queryAdapterAcceptsOnlyPreparedQuestion() {
        var vector = new float[] { 1, 0, 0 }; var model = FixedEmbeddingModels.query("Q", vector); vector[0] = 50;
        assertThat(model.embed("Q")).containsExactly(1, 0, 0);
        assertThatThrownBy(() -> model.embed("Other")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void selectedStoreIsReadOnlyAndCannotRunDifferentQuery() {
        var store = new SelectedDocumentsStore("Q", List.of());
        assertThat(store.similaritySearch(SearchRequest.builder().query("Q").build())).isEmpty();
        assertThatThrownBy(() -> store.similaritySearch(SearchRequest.builder().query("Other").build())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(List.of())).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void retrievalOptionsHaveBoundsAndStableDefaults() {
        assertThat(new RagRequest("Q", null, null).topK()).isEqualTo(4);
        for (double threshold : new double[] { -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertThatThrownBy(() -> new RagRequest("Q", 4, threshold)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RagRequest("Q", 9, .5)).isInstanceOf(IllegalArgumentException.class);
    }
}
