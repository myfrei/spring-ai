package dev.myfrei.repopilot.rag;

import java.util.Optional;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.ingestion.IndexArtifactStore;
import dev.myfrei.repopilot.ingestion.OllamaEmbeddingGateway;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;

public class RagIndexPublisher {
    private final RagDatabase database;
    private final IndexArtifactStore artifactStore;
    private final CorpusCatalog corpus;
    private final OllamaEmbeddingGateway embeddings;
    private final String workspace;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public RagIndexPublisher(RagDatabase database, IndexArtifactStore artifactStore, CorpusCatalog corpus,
            OllamaEmbeddingGateway embeddings, String workspace) {
        if (!workspace.matches("[a-z][a-z0-9-]{0,39}")) throw new IllegalArgumentException("Invalid workspace ID");
        this.database = database; this.artifactStore = artifactStore; this.corpus = corpus; this.embeddings = embeddings; this.workspace = workspace;
    }

    public Publication publish() {
        var artifact = this.artifactStore.load().orElseThrow(() -> new RagProblem("INDEX_NOT_READY", "Сначала создайте embeddings-снимок в панели 2.2."));
        if (!artifact.corpusVersion().equals(this.corpus.snapshot().version())) {
            throw new RagProblem("STALE_INDEX", "Файл индекса относится к другой версии корпуса. Выполните индексацию заново.");
        }
        if (!artifact.embedding().equals(this.embeddings.identity())) {
            throw new RagProblem("INDEX_PROFILE_MISMATCH", "Профиль embeddings не совпадает с файлом. Перестройте индекс текущей моделью.");
        }
        this.database.ensureSchema();
        try {
            return this.database.writeTransaction().execute(transaction -> {
                this.database.jdbc().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, this.workspace);
                var previous = manifest();
                Integer rows = this.database.jdbc().queryForObject("SELECT count(*) FROM rag_vectors WHERE metadata->>'workspaceId'=?", Integer.class, this.workspace);
                if (previous.isPresent() && previous.get().fingerprint().equals(artifact.fingerprint()) && rows == artifact.chunks().size()) {
                    return new Publication(true, previous.get());
                }
                this.database.jdbc().update("DELETE FROM rag_vectors WHERE metadata->>'workspaceId'=?", this.workspace);
                var documents = artifact.chunks().stream().map(entry -> RagDocuments.document(this.workspace, artifact, entry)).toList();
                this.database.store(FixedEmbeddingModels.prepared(this.workspace, artifact)).add(documents);
                var manifest = new RagManifest(this.workspace, artifact.fingerprint(), artifact.corpusVersion(), artifact.embedding(), documents.size());
                this.database.jdbc().update("INSERT INTO rag_active_indexes(workspace_id,manifest) VALUES (?,?::jsonb) ON CONFLICT(workspace_id) DO UPDATE SET manifest=excluded.manifest",
                        this.workspace, this.mapper.writeValueAsString(manifest));
                return new Publication(false, manifest);
            });
        } catch (DataAccessException | org.springframework.transaction.TransactionException error) { throw RagDatabase.unavailable(); }
    }

    public Optional<RagManifest> manifest() {
        this.database.ensureSchema();
        try {
            return this.database.jdbc().query("SELECT manifest::text FROM rag_active_indexes WHERE workspace_id=?",
                    (row, index) -> this.mapper.readValue(row.getString(1), RagManifest.class), this.workspace).stream().findFirst();
        } catch (DataAccessException | org.springframework.transaction.TransactionException error) { throw RagDatabase.unavailable(); }
    }

    public String workspace() { return this.workspace; }
    public record Publication(boolean reused, RagManifest manifest) { }
}
