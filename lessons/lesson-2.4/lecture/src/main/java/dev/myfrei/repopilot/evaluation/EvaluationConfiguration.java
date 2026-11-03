package dev.myfrei.repopilot.evaluation;

import dev.myfrei.repopilot.chat.ChatStreamService;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.rag.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "repopilot.evaluation.enabled", havingValue = "true")
public class EvaluationConfiguration {
    @Bean EvaluationDataset evaluationDataset(ResourceLoader loader, CorpusCatalog corpus) { return new EvaluationDataset(loader, corpus); }
    @Bean RunStore runStore(@Value("${repopilot.evaluation.directory}") String directory) { return new RunStore(directory); }
    @Bean ExperimentProbe experimentProbe(RestClient.Builder builder, @Value("${spring.ai.ollama.base-url}") String url,
            @Value("${spring.ai.ollama.chat.model}") String model, @Value("${spring.ai.ollama.chat.temperature}") double temperature,
            @Value("${repopilot.context.window-tokens}") int window, @Value("${repopilot.context.output-reserve}") int output,
            @Value("${repopilot.context.safety-margin}") int margin, @Value("${repopilot.system-prompt}") String prompt, CorpusCatalog corpus, EvaluationDataset dataset, ObjectProvider<RagIndexPublisher> indexes) {
        return new ExperimentProbe(builder, url, model, temperature, window, output, margin, prompt, corpus, dataset, indexes);
    }
    @Bean ComparisonEngine comparisonEngine(CorpusCatalog corpus, ContextAssembler assembler, ObjectProvider<RagSearcher> searchers,
            ObjectProvider<RagPlanner> planners, ChatStreamService model) { return new ComparisonEngine(corpus, assembler, searchers, planners, model); }
    @Bean(destroyMethod = "close") ExperimentCoordinator experimentCoordinator(EvaluationDataset dataset, ExperimentProbe probe, ComparisonEngine engine, RunStore store) {
        return new ExperimentCoordinator(dataset, probe, engine, store);
    }
}
