package dev.myfrei.repopilot.ingestion;

import java.util.List;
import dev.myfrei.repopilot.context.CorpusDocument;
import dev.myfrei.repopilot.context.CorpusSnapshot;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MarkdownChunkerTest {
    private final MarkdownChunker chunker = new MarkdownChunker();
    @Test void zeroOverlapPreservesEveryCharacterAndSourceLineRange() {
        String text = "# Intro\n" + "Строка данных.\n".repeat(30);
        var chunks = split(text, ChunkingOptions.Strategy.FIXED_WINDOW, 80, 0);
        assertThat(String.join("", chunks.stream().map(Chunk::text).toList())).isEqualTo(text);
        assertThat(chunks.getFirst().startLine()).isEqualTo(1);
        assertThat(chunks.getLast().endLine()).isEqualTo(31);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().codePointCount(0, c.text().length())).isLessThanOrEqualTo(80));
    }
    @Test void overlapIsExactAndLoopAlwaysAdvances() {
        String text = "0123456789".repeat(20);
        var chunks = split(text, ChunkingOptions.Strategy.FIXED_WINDOW, 80, 15);
        assertThat(chunks).hasSize(3);
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).text();
            assertThat(chunks.get(i).text().substring(0, 15)).isEqualTo(previous.substring(previous.length() - 15));
        }
    }
    @Test void markdownRespectsHierarchyAndDoesNotJoinDifferentSections() {
        var chunks = split("# Root\nIntro\n## Setup\nJava\n### DB\nSQLite\n## Run\nStart\n", ChunkingOptions.Strategy.MARKDOWN_SECTIONS, 600, 0);
        assertThat(chunks).extracting(Chunk::section).containsExactly("Root", "Root > Setup", "Root > Setup > DB", "Root > Run");
        assertThat(chunks.get(1).text()).isEqualTo("## Setup\nJava\n");
        assertThat(chunks.get(2).startLine()).isEqualTo(5);
    }
    @Test void fencedCodeHeadingsAreNotSectionsAndClosingFenceLengthMatters() {
        String text = "# Root\n````java\n## Fake\n```\n### Still code\n````\n## Real\nDone\n";
        var chunks = split(text, ChunkingOptions.Strategy.MARKDOWN_SECTIONS, 600, 0);
        assertThat(chunks).extracting(Chunk::section).containsExactly("Root", "Root > Real");
        assertThat(chunks.getFirst().text()).contains("## Fake", "### Still code");
    }
    @Test void emojiAreNeverSplitIntoUnpairedSurrogates() {
        String text = "🙂".repeat(150);
        var chunks = split(text, ChunkingOptions.Strategy.FIXED_WINDOW, 64, 0);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text().startsWith("🙂")).isTrue(); assertThat(c.text().endsWith("🙂")).isTrue();
            assertThat(c.text().codePointCount(0, c.text().length())).isLessThanOrEqualTo(64);
        });
        assertThat(String.join("", chunks.stream().map(Chunk::text).toList())).isEqualTo(text);
    }
    @Test void idsRepeatButChangeWithDocumentContentOrChunkingPolicy() {
        var first = split("# Root\n" + "text ".repeat(50), ChunkingOptions.Strategy.FIXED_WINDOW, 80, 0);
        assertThat(split("# Root\n" + "text ".repeat(50), ChunkingOptions.Strategy.FIXED_WINDOW, 80, 0)).isEqualTo(first);
        assertThat(split("# Root\nChanged " + "text ".repeat(50), ChunkingOptions.Strategy.FIXED_WINDOW, 80, 0).getFirst().id()).isNotEqualTo(first.getFirst().id());
        assertThat(split("# Root\n" + "text ".repeat(50), ChunkingOptions.Strategy.MARKDOWN_SECTIONS, 80, 0).getFirst().id()).isNotEqualTo(first.getFirst().id());
        assertThat(first).allSatisfy(c -> assertThat(c.id()).matches("[0-9a-f]{64}"));
        var doc = document("# Root\nText");
        var renamed = new CorpusDocument(doc.id(), doc.path(), "Renamed title", doc.sha256(), doc.text());
        var policy = new ChunkingOptions(ChunkingOptions.Strategy.FIXED_WINDOW, 80, 0);
        assertThat(chunker.split(new CorpusSnapshot("v", List.of(renamed)), policy).getFirst().id())
                .isNotEqualTo(chunker.split(new CorpusSnapshot("v", List.of(doc)), policy).getFirst().id());
    }
    @Test void unrelatedCorpusVersionDoesNotRenameUnchangedDocumentChunks() {
        var doc = document("# Root\nText\n"); var options = new ChunkingOptions(ChunkingOptions.Strategy.MARKDOWN_SECTIONS, 80, 0);
        var a = chunker.split(new CorpusSnapshot("v1", List.of(doc)), options).getFirst();
        var b = chunker.split(new CorpusSnapshot("v2", List.of(doc)), options).getFirst();
        assertThat(a.id()).isEqualTo(b.id()); assertThat(a.corpusVersion()).isNotEqualTo(b.corpusVersion());
    }
    @Test void rejectsInvalidOverlapSizeAndExcessiveChunkCount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingOptions(ChunkingOptions.Strategy.FIXED_WINDOW, 64, 64));
        assertThatIllegalArgumentException().isThrownBy(() -> new ChunkingOptions(ChunkingOptions.Strategy.FIXED_WINDOW, 63, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> split("x".repeat(2000), ChunkingOptions.Strategy.FIXED_WINDOW, 64, 63));
    }
    private List<Chunk> split(String text, ChunkingOptions.Strategy strategy, int size, int overlap) {
        return chunker.split(new CorpusSnapshot("v1", List.of(document(text))), new ChunkingOptions(strategy, size, overlap));
    }
    static CorpusDocument document(String text) { return new CorpusDocument("doc", "D.md", "Title", Hashes.sha256(text), text); }
}
