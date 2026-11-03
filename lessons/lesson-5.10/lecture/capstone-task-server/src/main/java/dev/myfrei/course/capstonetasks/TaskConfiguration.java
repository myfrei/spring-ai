package dev.myfrei.course.capstonetasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class TaskConfiguration {
  @Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
  JsonMapper mcpServerJsonMapper() {
    return JsonMapper.builder()
        .changeDefaultPropertyInclusion(
            inclusion ->
                JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
        .build();
  }

  @Bean
  static StrictToolSchemaPostProcessor schemas(ObjectProvider<ToolInputSchemas> providers) {
    return new StrictToolSchemaPostProcessor(providers);
  }

  @Bean
  @Primary
  McpSyncServerCustomizer rawArguments() {
    return builder ->
        builder
            .jsonMapper(TaskServerJson.mapper())
            .immediateExecution(true)
            .validateToolInputs(false);
  }

  @Bean
  FilterRegistrationBean<McpAccessFilter> access(@Value("${stockroom.access-token}") String token) {
    var bean = new FilterRegistrationBean<>(new McpAccessFilter(token));
    bean.addUrlPatterns("/*");
    bean.setAsyncSupported(true);
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return bean;
  }
}
