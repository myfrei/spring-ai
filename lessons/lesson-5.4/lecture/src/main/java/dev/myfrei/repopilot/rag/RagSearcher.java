package dev.myfrei.repopilot.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.ingestion.EmbeddingIdentity;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.ingestion.IngestionProblem;
import dev.myfrei.repopilot.ingestion.OllamaEmbeddingGateway;
import dev.myfrei.repopilot.ingestion.VectorValidator;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;

public class RagSearcher {
    private final RagDatabase database;
    private final RagIndexPublisher index;
    private final CorpusCatalog corpus;
    private final OllamaEmbeddingGateway embeddings;
    private final VectorValidator validator;

    public RagSearcher(RagDatabase database, RagIndexPublisher index, CorpusCatalog corpus,
            OllamaEmbeddingGateway embeddings, VectorValidator validator) {
        this.database = database; this.index = index; this.corpus = corpus; this.embeddings = embeddings; this.validator = validator;
    }

    public RagSearchResult search(RagRequest request) {
        return search(request,()->{});
    }

    public RagSearchResult search(RagRequest request,Runnable embeddingAttempt) {
        if (request.message() == null || request.message().isBlank() || request.message().length() > 4000) throw new IllegalArgumentException("Invalid question");
        RagManifest before = requireManifest();
        try {
            EmbeddingIdentity identity = this.embeddings.identity();
            requireCompatible(before, identity);
            embeddingAttempt.run();
            var validated = this.validator.validate(this.embeddings.query(request.message()), 1, identity.dimensions()).getFirst();
            if (!identity.equals(this.embeddings.identity())) throw new RagProblem("MODEL_CHANGED", "Embedding-модель изменилась во время поиска. Повторите запрос после проверки индекса.");
            float[] query = new float[validated.size()]; for (int i = 0; i < query.length; i++) query[i] = validated.get(i);
            return this.database.readTransaction().execute(transaction -> {
                RagManifest current = requireManifest();
                if (!before.fingerprint().equals(current.fingerprint())) throw new RagProblem("INDEX_CHANGED", "Индекс обновился во время поиска. Повторите запрос.");
                var filters = new FilterExpressionBuilder();
                var filter = filters.and(filters.eq("workspaceId", current.workspace()), filters.and(
                        filters.eq("corpusVersion", current.corpusVersion()), filters.and(
                        filters.eq("fingerprint", current.fingerprint()), filters.eq("embeddingDigest", identity.digest())))).build();
                var search = SearchRequest.builder().query(request.message()).topK(request.topK()).similarityThreshold(request.threshold()).filterExpression(filter).build();
                List<Document> documents = this.database.store(FixedEmbeddingModels.query(request.message(), query)).similaritySearch(search);
                var hits = new ArrayList<RagHit>();
                for (Document document : documents) hits.add(hit(document, "S" + (hits.size() + 1)));
                return new RagSearchResult(current, hits);
            });
        } catch (IngestionProblem error) { throw new RagProblem(error.code(), error.getMessage()); }
        catch (DataAccessException | org.springframework.transaction.TransactionException error) { throw RagDatabase.unavailable(); }
    }

    public String source(String fingerprint, String chunkId) {
        if (!fingerprint.matches("[0-9a-f]{64}") || !chunkId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source identity");
        this.database.ensureSchema();
        try {
            return this.database.readTransaction().execute(transaction -> {
                RagManifest manifest = requireManifest();
                if (!manifest.fingerprint().equals(fingerprint)) throw new RagProblem("NOT_FOUND", "Эта версия источника больше не активна.");
                String id = RagDocuments.rowId(manifest.workspace(), fingerprint, chunkId);
                var documents = this.database.jdbc().query("SELECT content,metadata::text FROM rag_vectors WHERE id=? AND metadata->>'workspaceId'=?",
                        (row, index) -> Document.builder().id(id).text(row.getString(1)).metadata(
                                JsonMapper.builder().build().readValue(row.getString(2), Map.class)).score(0.0).build(), id, manifest.workspace());
                if (documents.isEmpty()) throw new RagProblem("NOT_FOUND", "Фрагмент не найден.");
                var hit = hit(documents.getFirst(), "source");
                return hit.source().path() + ":" + hit.source().startLine() + "–" + hit.source().endLine()
                        + "\nchunkId=" + chunkId + "\ntextHash=" + hit.source().textHash() + "\n\n" + hit.text();
            });
        } catch (DataAccessException | org.springframework.transaction.TransactionException error) { throw RagDatabase.unavailable(); }
    }

    private RagManifest requireManifest() {
        RagManifest manifest = this.index.manifest().orElseThrow(() -> new RagProblem("INDEX_NOT_READY", "Сначала опубликуйте подготовленный индекс в pgvector."));
        if (!manifest.corpusVersion().equals(this.corpus.snapshot().version())) throw new RagProblem("STALE_INDEX", "Версия корпуса не совпадает с опубликованным индексом. Повторите ingestion и публикацию.");
        return manifest;
    }
    private static void requireCompatible(RagManifest manifest, EmbeddingIdentity identity) {
        if (!manifest.embedding().equals(identity)) throw new RagProblem("INDEX_PROFILE_MISMATCH", "Индекс построен другим encoder-профилем. Перестройте и опубликуйте индекс.");
    }
    private RagHit hit(Document document, String label) {
        var metadata = document.getMetadata();
        String documentId = text(metadata, "documentId");
        var original = this.corpus.document(documentId);
        String content = document.getText();
        String hash = text(metadata, "textHash");
        String chunkId = text(metadata, "chunkId");
        int start = number(metadata, "startLine"), end = number(metadata, "endLine");
        Double score = document.getScore();
        if (content == null || !Hashes.sha256(content).equals(hash) || !chunkId.matches("[0-9a-f]{64}")
                || !original.sha256().equals(text(metadata, "documentHash")) || !original.path().equals(text(metadata, "path"))
                || start < 1 || end < start || score == null || !Double.isFinite(score)) {
            throw new RagProblem("INDEX_CORRUPT", "Метаданные найденного фрагмента не прошли проверку.");
        }
        return new RagHit(new RagSource(label, chunkId, documentId, original.path(), text(metadata, "title"),
                text(metadata, "section"), start, end, hash, score), content);
    }
    private static String text(Map<String, Object> metadata, String key) {
        if (metadata.get(key) instanceof String value) return value;
        throw new RagProblem("INDEX_CORRUPT", "В найденном фрагменте отсутствуют обязательные метаданные.");
    }
    private static int number(Map<String, Object> metadata, String key) {
        if (metadata.get(key) instanceof Number value) return value.intValue();
        throw new RagProblem("INDEX_CORRUPT", "В найденном фрагменте отсутствуют номера строк.");
    }
}
