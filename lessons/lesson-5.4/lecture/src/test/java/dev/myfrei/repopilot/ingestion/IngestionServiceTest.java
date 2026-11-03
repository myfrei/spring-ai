package dev.myfrei.repopilot.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.context.CorpusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IngestionServiceTest {
    @TempDir Path directory;
    private CorpusCatalog corpus;
    private OllamaEmbeddingGateway gateway;
    private IngestionService ingestion;
    private Path file;
    private final ChunkingOptions options = new ChunkingOptions(ChunkingOptions.Strategy.MARKDOWN_SECTIONS, 80, 0);
    private final EmbeddingIdentity original = new EmbeddingIdentity("fixture:1", "a".repeat(64), 3, OllamaEmbeddingGateway.INPUT_FORMAT);
    @BeforeEach void setup() {
        corpus = mock(CorpusCatalog.class); gateway = mock(OllamaEmbeddingGateway.class); file = directory.resolve("index.json");
        when(corpus.snapshot()).thenReturn(snapshot("v1", "# One\n" + "text ".repeat(60)));
        when(gateway.identity()).thenReturn(original); when(gateway.configuredModel()).thenReturn("fixture:1");
        when(gateway.embed(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(ignored -> new float[] {1, 0, 0}).toList());
        ingestion = new IngestionService(corpus, new MarkdownChunker(), gateway, new VectorValidator(), new IndexArtifactStore(file.toString()), 2);
    }
    @Test void previewAndStatusDoNotCallEmbeddingRuntime() {
        assertThat(ingestion.preview(options).chunks()).isNotEmpty(); assertThat(ingestion.status().indexed()).isFalse();
        verify(gateway, never()).identity(); verify(gateway, never()).embed(anyList());
    }
    @Test void repeatRunReusesArtifactWithoutEmbeddingCallsOrRewrite() throws Exception {
        var first = ingestion.index(options); byte[] bytes = Files.readAllBytes(file);
        clearInvocations(gateway);
        var repeat = ingestion.index(options);
        assertThat(repeat.reused()).isTrue(); assertThat(repeat.fingerprint()).isEqualTo(first.fingerprint());
        verify(gateway, never()).embed(anyList()); assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
    }
    @Test void changedCorpusReplacesWholeSnapshotAndDropsOldChunks() {
        ingestion.index(options); var old = new IndexArtifactStore(file.toString()).load().orElseThrow();
        when(corpus.snapshot()).thenReturn(snapshot("v2", "# New\nNew document"));
        assertThat(ingestion.index(options).reused()).isFalse();
        var fresh = new IndexArtifactStore(file.toString()).load().orElseThrow();
        assertThat(fresh.corpusVersion()).isEqualTo("v2");
        assertThat(fresh.chunks()).hasSize(1).extracting(e -> e.chunk().id()).doesNotContainAnyElementsOf(old.chunks().stream().map(e -> e.chunk().id()).toList());
    }
    @Test void digestAndSplitterChangesRequireNewFingerprint() {
        var first = ingestion.index(options);
        var changed = new EmbeddingIdentity("fixture:1", "b".repeat(64), 3, original.inputFormat()); when(gateway.identity()).thenReturn(changed);
        var second = ingestion.index(options); assertThat(second.reused()).isFalse(); assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
        var third = ingestion.index(new ChunkingOptions(ChunkingOptions.Strategy.FIXED_WINDOW, 120, 10));
        assertThat(third.fingerprint()).isNotEqualTo(second.fingerprint());
    }
    @Test void dimensionChangeRebuildsInsteadOfMixingVectorSpaces() {
        ingestion.index(options);
        when(gateway.identity()).thenReturn(new EmbeddingIdentity("fixture:2", "b".repeat(64), 2, original.inputFormat()));
        when(gateway.embed(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(ignored -> new float[] {0, 1}).toList());
        assertThat(ingestion.index(options).embedding().dimensions()).isEqualTo(2);
        assertThat(new IndexArtifactStore(file.toString()).load().orElseThrow().chunks()).allSatisfy(e -> assertThat(e.vector()).hasSize(2));
    }
    @Test void failedBatchPreservesPreviousFileAndReleasesBusyFlag() throws Exception {
        ingestion.index(options); byte[] previous = Files.readAllBytes(file);
        when(corpus.snapshot()).thenReturn(snapshot("v2", "# New\n" + "new ".repeat(100)));
        when(gateway.embed(anyList())).thenReturn(List.of(new float[] {1, 0, 0}, new float[] {1, 0, 0}))
                .thenThrow(new IngestionProblem("EMBEDDING_UNAVAILABLE", "Fixture failure"));
        assertThatThrownBy(() -> ingestion.index(options)).isInstanceOf(IngestionProblem.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(previous); assertThat(ingestion.status().running()).isFalse();
    }
    @Test void modelDigestChangedDuringRunPreventsPublication() {
        when(gateway.identity()).thenReturn(original, new EmbeddingIdentity("fixture:1", "b".repeat(64), 3, original.inputFormat()));
        assertThatThrownBy(() -> ingestion.index(options)).isInstanceOf(IngestionProblem.class).extracting("code").isEqualTo("MODEL_CHANGED");
        assertThat(file).doesNotExist();
    }
    @Test void overlappingIndexRequestsAreRejected() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(gateway.embed(anyList())).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return ((List<?>) call.getArgument(0)).stream().map(ignored -> new float[] {1, 0, 0}).toList();
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> ingestion.index(options));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> ingestion.index(options)).isInstanceOf(IngestionProblem.class).extracting("code").isEqualTo("INDEX_BUSY");
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS).chunkCount()).isPositive();
        }
    }
    private static CorpusSnapshot snapshot(String version, String text) { return new CorpusSnapshot(version, List.of(MarkdownChunkerTest.document(text))); }
}
