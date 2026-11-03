package dev.myfrei.repopilot.http;

import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.mcp.TaskMcpHandlers;
import dev.myfrei.repopilot.mcp.TaskServerJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods=false)
public class TaskHttpConfiguration {
    // Public Spring AI customization point: preserve explicit null map entries during
    // transport read -> convertValue so raw argument validation can reject them.
    @Bean(name="mcpServerJsonMapper", defaultCandidate=false)
    JsonMapper mcpServerJsonMapper() {
        return JsonMapper.builder().changeDefaultPropertyInclusion(inclusion ->
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)).build();
    }
    @Bean TaskCatalog taskCatalog() { return new TaskCatalog(); }
    @Bean TaskMcpHandlers taskMcpHandlers(TaskCatalog catalog) { return new TaskMcpHandlers(catalog); }
    @Bean static StrictToolSchemaPostProcessor strictToolSchemas(ObjectProvider<ToolInputSchemas> providers) { return new StrictToolSchemaPostProcessor(providers); }
        // Spring AI 2.0.2 also provides a servlet customizer; select this explicit combined policy.
    @Bean @Primary McpSyncServerCustomizer strictInputValidation() { return builder->builder.jsonMapper(TaskServerJson.mapper()).immediateExecution(true).validateToolInputs(false); }
    @Bean FilterRegistrationBean<McpAccessFilter> mcpAccessFilter(@Value("${taskshelf.access-token}") String token) {
        var bean=new FilterRegistrationBean<>(new McpAccessFilter(token));bean.addUrlPatterns("/*");bean.setAsyncSupported(true);bean.setOrder(Ordered.HIGHEST_PRECEDENCE+10);return bean;
    }
}
