package dev.myfrei.repopilot.context;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorpusCatalogTest {
    @Test void stableVersionDoesNotDependOnManifestOrderOrLineEndings() {
        var a = catalog("a|A.md|A\nb|B.md|B\n", Map.of("A.md", "# A\nText\n", "B.md", "# B\n"));
        var b = catalog("b|B.md|B\r\na|A.md|A\r\n", Map.of("A.md", "# A\r\nText\r\n", "B.md", "# B\r\n"));
        assertThat(a.snapshot()).isEqualTo(b.snapshot());
        assertThat(a.snapshot().version()).matches("[0-9a-f]{64}");
        assertThat(a.snapshot().documents()).extracting(CorpusDocument::id).containsExactly("a", "b");
    }

    @Test void changingContentChangesDocumentHashAndCorpusVersion() {
        var old = catalog("a|A.md|A", Map.of("A.md", "port 8091"));
        var fresh = catalog("a|A.md|A", Map.of("A.md", "port 9091"));
        assertThat(old.document("a").sha256()).isNotEqualTo(fresh.document("a").sha256());
        assertThat(old.snapshot().version()).isNotEqualTo(fresh.snapshot().version());
    }

    @Test void changingSourceIdentityAlsoChangesVersion() {
        var old = catalog("a|A.md|Old title", Map.of("A.md", "Same text"));
        var fresh = catalog("a|A.md|New title", Map.of("A.md", "Same text"));
        assertThat(old.document("a").sha256()).isEqualTo(fresh.document("a").sha256());
        assertThat(old.snapshot().version()).isNotEqualTo(fresh.snapshot().version());
    }

    @Test void rejectsPathsOutsideExplicitBundledMarkdownNamespace() {
        for (String path : new String[] {"../secret.md", "/etc/file.md", "file:secret.md", "a\\b.md", "A.txt", "x//A.md"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> catalog("a|" + path + "|A", Map.of()));
        }
    }

    @Test void rejectsDuplicatesEmptyDocumentsAndResourceOverflow() {
        assertThatIllegalArgumentException().isThrownBy(() -> catalog("a|A.md|A\na|B.md|B", Map.of("A.md", "A")));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog("a|A.md|A", Map.of("A.md", " ")));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog("a|A.md|A", Map.of("A.md", "x".repeat(16_385))));
    }

    @Test void rejectsInvalidUtf8InsteadOfReplacingBytes() {
        var loader = mock(ResourceLoader.class);
        when(loader.getResource("classpath:corpus/manifest.txt")).thenReturn(bytes("a|A.md|A"));
        when(loader.getResource("classpath:corpus/A.md")).thenReturn(new ByteArrayResource(new byte[] {(byte) 0xc3, 0x28}));
        assertThatIllegalStateException().isThrownBy(() -> new CorpusCatalog(loader));
    }

    @Test void snapshotIsImmutableAndUnknownIdCannotOpenAPath() {
        var catalog = catalog("a|A.md|A", Map.of("A.md", "Text"));
        assertThatThrownBy(() -> catalog.snapshot().documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.document("../A.md")).isInstanceOf(dev.myfrei.repopilot.conversation.ConversationProblem.class);
    }

    private static CorpusCatalog catalog(String manifest, Map<String, String> files) {
        var loader = mock(ResourceLoader.class);
        when(loader.getResource("classpath:corpus/manifest.txt")).thenReturn(bytes(manifest));
        files.forEach((path, text) -> when(loader.getResource("classpath:corpus/" + path)).thenReturn(bytes(text)));
        return new CorpusCatalog(loader);
    }
    private static ByteArrayResource bytes(String text) { return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)); }
}
