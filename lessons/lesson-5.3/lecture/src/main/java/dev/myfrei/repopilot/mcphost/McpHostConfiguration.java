package dev.myfrei.repopilot.mcphost;

import java.net.URI;
import java.time.Duration;
import dev.myfrei.repopilot.advisor.RagPreparationService;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name={"repopilot.mcp-host.enabled","repopilot.rag.enabled"},havingValue="true")
public class McpHostConfiguration {
    @Bean(destroyMethod="close")public LazyMcpTaskGateway remoteTaskGateway(@Value("${repopilot.mcp-host.server-url}")URI base,
            @Value("${TASKSHELF_MCP_TOKEN:course-local-token}")String token,@Value("${repopilot.mcp-host.timeout:5s}")Duration timeout){return new LazyMcpTaskGateway(base,token,timeout);}
    @Bean public McpHostEngine mcpHostEngine(RemoteTaskGateway gateway,RagSearcher searcher,Utf8TokenEstimator estimator,ChatClient.Builder builder,
            RestClient.Builder rest,@Value("${repopilot.system-prompt}")String system,@Value("${spring.ai.ollama.base-url}")String url,
            @Value("${spring.ai.ollama.chat.model}")String model,@Value("${repopilot.context.window-tokens}")int window,@Value("${repopilot.context.safety-margin:256}")int margin){
        var planner=new RagPlanner(system+"\n"+McpHostEngine.TASK_RULES,new ContextBudget(window,256,Math.max(256,margin)),estimator);
        return new McpHostEngine(gateway,new RagPreparationService(searcher,planner),builder,new OllamaToolIdentity(rest,url,model),model,window);
    }
}
