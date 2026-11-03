package dev.myfrei.course.capstonetasks;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Refines public annotation-generated specifications; never accesses private framework
 * fields/names.
 */
public final class StrictToolSchemaPostProcessor implements BeanPostProcessor {
  private final ObjectProvider<ToolInputSchemas> providers;

  public StrictToolSchemaPostProcessor(ObjectProvider<ToolInputSchemas> providers) {
    this.providers = providers;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (!(bean instanceof List<?> values)
        || values.isEmpty()
        || !values.stream().allMatch(SyncToolSpecification.class::isInstance)) return bean;
    var schemas = new LinkedHashMap<String, Map<String, Object>>();
    this.providers
        .orderedStream()
        .forEach(
            provider ->
                provider
                    .toolInputSchemas()
                    .forEach(
                        (name, schema) -> {
                          if (schemas.putIfAbsent(name, Map.copyOf(schema)) != null)
                            throw new IllegalStateException("Duplicate tool schema contribution");
                        }));
    return values.stream()
        .map(
            value -> {
              var spec = (SyncToolSpecification) value;
              var tool = spec.tool();
              var schema = schemas.get(tool.name());
              if (schema == null) return spec;
              var refined =
                  new McpSchema.Tool(
                      tool.name(),
                      tool.title(),
                      tool.description(),
                      schema,
                      tool.outputSchema(),
                      tool.annotations(),
                      tool.meta(),
                      tool.icons());
              return new SyncToolSpecification(refined, spec.callHandler());
            })
        .toList();
  }
}
