package dev.myfrei.repopilot.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class IndexArtifactStore {
    private final Path file;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public IndexArtifactStore(@Value("${repopilot.ingestion.index-file}") String file) {
        this.file = Path.of(file).toAbsolutePath();
    }

    public Optional<IndexArtifact> load() {
        if (!Files.exists(this.file)) return Optional.empty();
        try {
            if (Files.size(this.file) > 16_777_216) throw new IOException("Artifact too large");
            IndexArtifact artifact = this.mapper.readValue(Files.readAllBytes(this.file), IndexArtifact.class);
            validate(artifact);
            return Optional.of(artifact);
        } catch (IOException | RuntimeException ex) {
            throw new IngestionProblem("INDEX_CORRUPT", "Сохранённый индекс не удалось прочитать или проверить. Исходный файл не изменён.");
        }
    }

    public void save(IndexArtifact artifact) {
        validate(artifact);
        Path temporary = null;
        try {
            Files.createDirectories(this.file.getParent());
            byte[] bytes = this.mapper.writeValueAsBytes(artifact);
            if (bytes.length > 16_777_216) throw new IOException("Artifact too large");
            temporary = Files.createTempFile(this.file.getParent(), "index-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            throw new IngestionProblem("INDEX_WRITE_FAILED", "Не удалось атомарно сохранить индекс. Проверьте каталог и файловую систему.");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Owned temporary file only. */ }
            }
        }
    }

    private static void validate(IndexArtifact artifact) {
        if (artifact == null || artifact.formatVersion() != 1 || artifact.chunks().isEmpty() || artifact.chunks().size() > 512
                || !artifact.fingerprint().equals(IndexArtifact.fingerprint(artifact.corpusVersion(), artifact.options(), artifact.embedding()))) {
            throw new IllegalArgumentException("Invalid index manifest");
        }
        var ids = new HashSet<String>();
        for (var entry : artifact.chunks()) {
            if (!ids.add(entry.chunk().id()) || !entry.chunk().corpusVersion().equals(artifact.corpusVersion())
                    || entry.vector().size() != artifact.embedding().dimensions()
                    || entry.vector().stream().anyMatch(value -> !Float.isFinite(value))
                    || entry.vector().stream().allMatch(value -> value == 0)) {
                throw new IllegalArgumentException("Invalid saved vector/chunk");
            }
        }
    }
}
