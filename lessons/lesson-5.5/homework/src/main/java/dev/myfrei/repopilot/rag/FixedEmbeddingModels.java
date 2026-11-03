package dev.myfrei.repopilot.rag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import dev.myfrei.repopilot.ingestion.IndexArtifact;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public final class FixedEmbeddingModels {
    private FixedEmbeddingModels() { }
    private abstract static class Fixed implements EmbeddingModel {
        @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException("No inference in fixed embedding model"); }
        @Override public float[] embed(Document document) { throw new UnsupportedOperationException("Use prepared document batches"); }
    }

    public static EmbeddingModel prepared(String workspace, IndexArtifact artifact) {
        Map<String, float[]> vectors = artifact.chunks().stream().collect(Collectors.toMap(
                entry -> RagDocuments.rowId(workspace, artifact.fingerprint(), entry.chunk().id()),
                entry -> { float[] values = new float[entry.vector().size()]; for (int i = 0; i < values.length; i++) values[i] = entry.vector().get(i); return values; }));
        return new Fixed() {
            @Override public int dimensions() { return artifact.embedding().dimensions(); }
            @Override public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy strategy) {
                return documents.stream().map(document -> {
                    float[] vector = vectors.get(document.getId());
                    if (vector == null) throw new IllegalArgumentException("Document not in prepared artifact");
                    return vector.clone();
                }).toList();
            }
        };
    }

    public static EmbeddingModel query(String question, float[] vector) {
        float[] snapshot = vector.clone();
        return new Fixed() {
            @Override public int dimensions() { return snapshot.length; }
            @Override public float[] embed(String text) {
                if (!question.equals(text)) throw new IllegalArgumentException("Query does not match prepared vector");
                return snapshot.clone();
            }
        };
    }
}
