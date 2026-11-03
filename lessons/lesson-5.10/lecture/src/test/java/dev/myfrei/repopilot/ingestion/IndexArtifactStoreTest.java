package dev.myfrei.repopilot.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.myfrei.repopilot.context.CorpusSnapshot;
import static org.assertj.core.api.Assertions.*;

class IndexArtifactStoreTest {
    @TempDir Path directory;
    @Test void emptyStoreDoesNotCreateFiles() {
        assertThat(new IndexArtifactStore(directory.resolve("index.json").toString()).load()).isEmpty();
        assertThat(directory.resolve("index.json")).doesNotExist();
    }
    @Test void snapshotSurvivesReopeningAndLeavesNoTemporaryFiles() throws Exception {
        Path file = directory.resolve("index.json"); var store = new IndexArtifactStore(file.toString());
        var artifact = artifact("First"); store.save(artifact);
        assertThat(new IndexArtifactStore(file.toString()).load()).contains(artifact);
        try (var files = Files.list(directory)) { assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("index.json"); }
    }
    @Test void successfulReplacementRemovesObsoleteChunkIds() {
        var store = new IndexArtifactStore(directory.resolve("index.json").toString());
        var first = artifact("Old"); var second = artifact("New"); store.save(first); store.save(second);
        assertThat(store.load()).contains(second);
        assertThat(store.load().orElseThrow().chunks()).extracting(e -> e.chunk().id()).doesNotContain(first.chunks().getFirst().chunk().id());
    }
    @Test void corruptInputFailsExplicitlyWithoutOverwritingIt() throws Exception {
        Path file = directory.resolve("index.json"); Files.writeString(file, "broken-json");
        assertThatThrownBy(() -> new IndexArtifactStore(file.toString()).load()).isInstanceOf(IngestionProblem.class)
                .extracting("code").isEqualTo("INDEX_CORRUPT");
        assertThat(Files.readString(file)).isEqualTo("broken-json");
    }
    static IndexArtifact artifact(String text) {
        var doc = MarkdownChunkerTest.document(text); var options = new ChunkingOptions(ChunkingOptions.Strategy.FIXED_WINDOW, 80, 0);
        var chunk = new MarkdownChunker().split(new CorpusSnapshot(Hashes.sha256(text), List.of(doc)), options).getFirst();
        var identity = new EmbeddingIdentity("fixture:1", "a".repeat(64), 3, OllamaEmbeddingGateway.INPUT_FORMAT);
        return new IndexArtifact(1, IndexArtifact.fingerprint(chunk.corpusVersion(), options, identity), chunk.corpusVersion(), options,
                identity, List.of(new EmbeddedChunk(chunk, List.of(1f, 0f, 0f))));
    }
}
