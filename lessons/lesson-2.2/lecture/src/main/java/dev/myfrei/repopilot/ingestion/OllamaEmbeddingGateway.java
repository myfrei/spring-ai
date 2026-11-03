package dev.myfrei.repopilot.ingestion;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OllamaEmbeddingGateway {
    public static final String INPUT_FORMAT = "embeddinggemma-title-text-v1";
    private final EmbeddingModel embeddings;
    private final RestClient client;
    private final String model;
    private final int dimensions;
    private final int contextWindow;

    public OllamaEmbeddingGateway(EmbeddingModel embeddings, RestClient.Builder builder,
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.embedding.model}") String model,
            @Value("${repopilot.ingestion.dimensions}") int dimensions,
            @Value("${spring.ai.ollama.embedding.num-ctx}") int contextWindow) {
        if (dimensions < 1 || dimensions > 4096 || contextWindow < 1) throw new IllegalArgumentException("Invalid embedding dimensions/context");
        this.embeddings = embeddings; this.client = builder.baseUrl(baseUrl).build();
        this.model = model; this.dimensions = dimensions;
        this.contextWindow = contextWindow;
    }

    public EmbeddingIdentity identity() {
        try {
            Tags tags = this.client.get().uri("/api/tags").retrieve().body(Tags.class);
            if (tags != null && tags.models() != null) {
                for (Tag tag : tags.models()) {
                    if (this.model.equals(tag.name()) || this.model.equals(tag.model())) {
                        if (tag.digest() == null || !tag.digest().matches("[0-9a-f]{64}")) break;
                        return new EmbeddingIdentity(this.model, tag.digest(), this.dimensions, INPUT_FORMAT);
                    }
                }
            }
        } catch (RuntimeException ex) {
            throw new IngestionProblem("EMBEDDING_UNAVAILABLE", "Не удалось проверить embedding-модель. Проверьте Ollama и локальный model tag.");
        }
        throw new IngestionProblem("MODEL_NOT_FOUND", "Embedding-модель с корректным digest не найдена. Сначала загрузите указанный tag в Ollama.");
    }

    public List<float[]> embed(List<Chunk> chunks) {
        List<String> texts = chunks.stream().map(this::input).toList();
        try {
            var options = OllamaEmbeddingOptions.builder().model(this.model).truncate(false)
                    .dimensions(this.dimensions).numCtx(this.contextWindow).build();
            var response = this.embeddings.call(new EmbeddingRequest(texts, options));
            if (!this.model.equals(response.getMetadata().getModel())) {
                throw new IngestionProblem("MODEL_CHANGED", "Embedding-ответ относится к другой модели. Новый снимок не опубликован.");
            }
            return response.getResults().stream().map(embedding -> embedding.getOutput()).toList();
        }
        catch (IngestionProblem ex) { throw ex; }
        catch (RuntimeException ex) {
            throw new IngestionProblem("EMBEDDING_UNAVAILABLE", "Не удалось получить embeddings. Проверьте модель и размер чанков; старый индекс сохранён.");
        }
    }

    public String input(Chunk chunk) { return "title: " + chunk.title() + " / " + chunk.section() + " | text: " + chunk.text(); }
    public String configuredModel() { return this.model; }
    public int configuredDimensions() { return this.dimensions; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tags(List<Tag> models) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String name, String model, String digest) { }
}
