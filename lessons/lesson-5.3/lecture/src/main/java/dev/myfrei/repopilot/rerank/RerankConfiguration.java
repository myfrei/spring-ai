package dev.myfrei.repopilot.rerank;

import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.evaluation.EvaluationDataset;
import dev.myfrei.repopilot.query.QueryRagPipeline;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name={"repopilot.rerank.enabled","repopilot.query.enabled","repopilot.rag.enabled"},havingValue="true")
public class RerankConfiguration {
    @Bean RerankModel rerankModel(ChatClient.Builder builder,RestClient.Builder rest,@Value("${spring.ai.ollama.base-url}") String url,
            @Value("${spring.ai.ollama.chat.model}") String model,@Value("${repopilot.context.window-tokens}") int window) { return new SpringRerankModel(builder,rest,url,model,window); }
    @Bean RerankService rerankService(RerankModel model,@Value("${repopilot.rerank.prompt-version}") String version) { return new RerankService(model,version); }
    @Bean RerankPipeline rerankPipeline(QueryRagPipeline queries,RerankService service,RagPlanner planner) { return new RerankPipeline(queries,service,planner); }
    @Bean RerankCallService rerankCallService(RerankPipeline pipeline,ChatClient.Builder builder) { return new RerankCallService(pipeline,builder); }
    @Bean RerankComparisonService rerankComparisonService(QueryRagPipeline queries,RerankCallService calls,RerankModel model,RagIndexPublisher index,CorpusCatalog corpus,ObjectProvider<EvaluationDataset> datasets) {
        return new RerankComparisonService(queries,calls,model,index,corpus,datasets);
    }
}
