package dev.myfrei.repopilot.rag;

import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.ingestion.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "repopilot.rag.enabled", havingValue = "true")
public class RagConfiguration {
    @Bean RagDatabase ragDatabase(@Value("${repopilot.rag.jdbc-url}") String url,
            @Value("${repopilot.rag.username}") String username, @Value("${repopilot.rag.password}") String password,
            @Value("${repopilot.ingestion.dimensions}") int dimensions) {
        return new RagDatabase(url, username, password, dimensions);
    }
    @Bean RagIndexPublisher ragIndexPublisher(RagDatabase database, IndexArtifactStore artifacts, CorpusCatalog corpus,
            OllamaEmbeddingGateway embeddings, @Value("${repopilot.rag.workspace}") String workspace) {
        return new RagIndexPublisher(database, artifacts, corpus, embeddings, workspace);
    }
    @Bean RagSearcher ragSearcher(RagDatabase database, RagIndexPublisher index, CorpusCatalog corpus,
            OllamaEmbeddingGateway embeddings, VectorValidator validator) {
        return new RagSearcher(database, index, corpus, embeddings, validator);
    }
    @Bean RagPlanner ragPlanner(@Value("${repopilot.system-prompt}") String system,
            @Value("${repopilot.context.window-tokens}") int window,
            @Value("${repopilot.context.output-reserve}") int output,
            @Value("${repopilot.context.safety-margin}") int margin, Utf8TokenEstimator estimator) {
        return new RagPlanner(system, new ContextBudget(window, output, margin), estimator);
    }
}
