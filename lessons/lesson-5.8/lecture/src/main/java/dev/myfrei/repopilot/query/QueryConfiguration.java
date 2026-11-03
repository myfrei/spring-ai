package dev.myfrei.repopilot.query;
import dev.myfrei.repopilot.rag.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.client.RestClient;
@Configuration
@ConditionalOnProperty(name={"repopilot.query.enabled","repopilot.rag.enabled"},havingValue="true")
public class QueryConfiguration{
 @Bean QueryModel queryModel(ChatClient.Builder builder,RestClient.Builder rest,@Value("${spring.ai.ollama.base-url}")String url,@Value("${spring.ai.ollama.chat.model}")String model,@Value("${repopilot.context.window-tokens}")int window){return new SpringQueryModel(builder,rest,url,model,window);}
 @Bean QueryPlanner queryPlanner(QueryModel model,@Value("${repopilot.query.prompt-version}")String version,@Value("${repopilot.system-prompt}")String system){return new QueryPlanner(model,version,system);}
 @Bean QueryRagPipeline queryRagPipeline(QueryPlanner query,RagSearcher searcher,RagPlanner rag){return new QueryRagPipeline(query,searcher,rag);}
 @Bean QueryCallService queryCallService(QueryRagPipeline pipeline,ChatClient.Builder builder){return new QueryCallService(pipeline,builder);}
}
