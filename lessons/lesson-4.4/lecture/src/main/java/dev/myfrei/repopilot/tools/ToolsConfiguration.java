package dev.myfrei.repopilot.tools;

import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name="repopilot.tools.enabled",havingValue="true")
public class ToolsConfiguration {
    @Bean TaskCatalog taskCatalog() { return new TaskCatalog(); }
    @Bean ToolModelIdentity toolModelIdentity(RestClient.Builder rest,@Value("${spring.ai.ollama.base-url}")String url,@Value("${spring.ai.ollama.chat.model}")String model) { return new OllamaToolIdentity(rest,url,model); }
    @Bean TaskToolsService taskToolsService(TaskCatalog catalog,ChatClient.Builder builder,ToolModelIdentity identity,
            @Value("${spring.ai.ollama.chat.model}")String model,@Value("${repopilot.context.window-tokens}")int window) { return new TaskToolsService(catalog,builder,identity,model,window); }
}
