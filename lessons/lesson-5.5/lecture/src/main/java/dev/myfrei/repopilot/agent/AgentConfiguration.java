package dev.myfrei.repopilot.agent;
import java.util.List;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.tools.OllamaToolIdentity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;
@Configuration
@ConditionalOnProperty(name="repopilot.agent.enabled",havingValue="true")
public class AgentConfiguration {
 @Bean public AgentStore agentStore(){return new AgentStore();}
 @Bean public AgentTool agentProjectInfoTool(){return TaskAgentTools.create(new TaskCatalog()).get(0);}
 @Bean public AgentTool agentListTasksTool(){return TaskAgentTools.create(new TaskCatalog()).get(1);}
 @Bean public AgentTool agentGetTaskTool(){return TaskAgentTools.create(new TaskCatalog()).get(2);}
 @Bean public AgentEngine agentEngine(ChatModel model,RestClient.Builder rest,List<AgentTool> tools,AgentStore store,
  @Value("${spring.ai.ollama.base-url}")String url,@Value("${spring.ai.ollama.chat.model}")String modelName,@Value("${repopilot.context.window-tokens}")int window){return new AgentEngine(model,new OllamaToolIdentity(rest,url,modelName),tools,store,modelName,window);}
}
