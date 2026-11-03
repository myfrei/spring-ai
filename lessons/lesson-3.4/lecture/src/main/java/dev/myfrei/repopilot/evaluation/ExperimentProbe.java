package dev.myfrei.repopilot.evaluation;

import java.util.List;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.context.Utf8TokenEstimator;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.ingestion.OllamaEmbeddingGateway.Tags;
import dev.myfrei.repopilot.rag.RagIndexPublisher;
import dev.myfrei.repopilot.rag.RagManifest;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

public class ExperimentProbe {
    private final RestClient client;
    private final String model;
    private final double temperature;
    private final int window;
    private final int output;
    private final int margin;
    private final String prompt;
    private final CorpusCatalog corpus;
    private final EvaluationDataset dataset;
    private final ObjectProvider<RagIndexPublisher> indexes;
    public ExperimentProbe(RestClient.Builder builder, String url, String model, double temperature, int window, int output, int margin,
            String prompt, CorpusCatalog corpus, EvaluationDataset dataset, ObjectProvider<RagIndexPublisher> indexes) {
        this.client = builder.baseUrl(url).build(); this.model = model; this.temperature = temperature; this.window = window;
        this.output = output; this.margin = margin; this.prompt = prompt; this.corpus = corpus; this.dataset = dataset; this.indexes = indexes;
    }
    public Conditions capture(List<Mode> modes) {
        String digest = null;
        try {
            Tags tags = this.client.get().uri("/api/tags").retrieve().body(Tags.class);
            if (tags != null && tags.models() != null) for (var tag : tags.models()) {
                if ((this.model.equals(tag.name()) || this.model.equals(tag.model())) && tag.digest() != null && tag.digest().matches("[0-9a-f]{64}")) digest = tag.digest();
            }
        } catch (RuntimeException error) { throw new EvaluationProblem("MODEL_UNAVAILABLE", "Не удалось проверить chat-модель в Ollama."); }
        if (digest == null) throw new EvaluationProblem("MODEL_NOT_FOUND", "Chat tag с digest не найден в Ollama. Проверьте локальную модель.");
        RagManifest rag = null;
        if (modes.contains(Mode.RAG)) {
            var index = this.indexes.getIfAvailable();
            if (index == null) throw new EvaluationProblem("RAG_DISABLED", "Для сравнения RAG включите repopilot.rag.enabled.");
            rag = index.manifest().orElseThrow(() -> new EvaluationProblem("INDEX_NOT_READY", "Сначала выполните ingestion и публикацию pgvector."));
            if (!rag.corpusVersion().equals(this.corpus.snapshot().version())) throw new EvaluationProblem("STALE_INDEX", "Корпус и RAG manifest не совпадают.");
        }
        return new Conditions(this.dataset.version(), this.corpus.snapshot().version(),
                new ChatProfile(this.model, digest, this.temperature, this.window, this.output, Hashes.sha256(this.prompt)), rag,
                Utf8TokenEstimator.NAME, this.window - this.output - this.margin, "lesson-3.4-v1", "NOT_MEASURED", "rotate-modes-v1; canonical-case-order; sequential");
    }
    public void requireUnchanged(Conditions expected, List<Mode> modes) {
        if (!expected.equals(capture(modes))) throw new EvaluationProblem("EXPERIMENT_CHANGED", "Модель или индекс изменились во время эксперимента. Результаты помечены, сравнение остановлено.");
    }
}
