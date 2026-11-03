package dev.myfrei.repopilot.tools;

import dev.myfrei.repopilot.ingestion.OllamaEmbeddingGateway.Tags;
import org.springframework.web.client.RestClient;

public class OllamaToolIdentity implements ToolModelIdentity {
    private final RestClient client;private final String model;
    public OllamaToolIdentity(RestClient.Builder builder,String url,String model) { this.client=builder.baseUrl(url).build();this.model=model; }
    @Override public String digest() {
        try {
            var tags=client.get().uri("/api/tags").retrieve().body(Tags.class);
            if (tags!=null&&tags.models()!=null) for (var tag:tags.models())
                if ((model.equals(tag.name())||model.equals(tag.model()))&&tag.digest()!=null&&tag.digest().matches("[0-9a-f]{64}")) return tag.digest();
        } catch (RuntimeException error) { throw unavailable(); }
        throw unavailable();
    }
    private static ToolsProblem unavailable() { return new ToolsProblem("TOOLS_IDENTITY_UNAVAILABLE","Не удалось проверить локальную chat-модель. /api/tools/workflow работает без LLM.",null); }
}
