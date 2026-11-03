package dev.myfrei.repopilot.agent;

import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Closed scalar schemas used by this course; not a general JSON Schema implementation. */
final class AgentToolRegistry {
  private final Map<String, AgentTool> tools;
  private final List<ToolCallback> callbacks;

  AgentToolRegistry(List<AgentTool> registrations) {
    if (registrations == null || registrations.size() > AgentEngine.MAX_TOOLS)
      throw new IllegalArgumentException("At most12 explicit tools");
    var map = new LinkedHashMap<String, AgentTool>();
    var bridges = new ArrayList<ToolCallback>();
    for (var tool : registrations) {
      if (tool == null
          || tool.name() == null
          || !tool.name().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
          || tool.description() == null
          || tool.description().isBlank()
          || tool.description().length() > 500
          || tool.inputSchema() == null
          || AgentJson.bytes(tool.inputSchema()) > 4096
          || map.putIfAbsent(tool.name(), tool) != null)
        throw new IllegalArgumentException("Unique bounded tool definitions required");
      schema(tool);
      var definition =
          ToolDefinition.builder()
              .name(tool.name())
              .description(tool.description())
              .inputSchema(tool.inputSchema())
              .build();
      bridges.add(
          new ToolCallback() {
            public ToolDefinition getToolDefinition() {
              return definition;
            }

            public String call(String ignored) {
              throw new IllegalStateException(
                  "Schema-only callback: AgentEngine owns every execution");
            }
          });
    }
    tools = Collections.unmodifiableMap(map);
    callbacks = List.copyOf(bridges);
  }

  List<ToolCallback> callbacks() {
    return callbacks;
  }

  AgentTool find(String name) {
    return tools.get(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schema(AgentTool tool) {
    Object parsed = AgentJson.parse(tool.inputSchema());
    if (!(parsed instanceof Map<?, ?> schema)
        || !"object".equals(schema.get("type"))
        || !Boolean.FALSE.equals(schema.get("additionalProperties"))
        || !(schema.get("properties") instanceof Map<?, ?> properties)
        || properties.size() > 8
        || !(schema.get("required") instanceof List<?> required)
        || !properties.keySet().containsAll(required)
        || new HashSet<>(required).size() != required.size())
      throw new IllegalArgumentException("Closed object schema required");
    for (var property : properties.values())
      if (!(property instanceof Map<?, ?> spec)
          || !Set.of("string", "integer", "number", "boolean").contains(spec.get("type")))
        throw new IllegalArgumentException("Course tools require flat scalar properties");
    return (Map<String, Object>) schema;
  }

  @SuppressWarnings("unchecked")
  String validateArguments(AgentTool tool, String raw) {
    if (raw == null || AgentJson.bytes(raw) > 4096)
      throw new IllegalArgumentException("Bounded arguments required");
    var schema = schema(tool);
    var properties = (Map<String, Object>) schema.get("properties");
    var required = new HashSet<String>((List<String>) schema.get("required"));
    var args = AgentJson.arguments(raw, properties.keySet(), required);
    for (var entry : args.entrySet()) {
      var spec = (Map<String, Object>) properties.get(entry.getKey());
      Object value = entry.getValue();
      boolean valid =
          switch ((String) spec.get("type")) {
            case "string" -> value instanceof String;
            case "integer" ->
                value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof java.math.BigInteger;
            case "number" ->
                value instanceof Number number && Double.isFinite(number.doubleValue());
            case "boolean" -> value instanceof Boolean;
            default -> false;
          };
      if (!valid || (spec.get("enum") instanceof List<?> values && !values.contains(value)))
        throw new IllegalArgumentException("Arguments do not match declared scalar types");
      if (value instanceof String text) {
        int length = text.codePointCount(0, text.length());
        if (spec.get("minLength") instanceof Number min && length < min.intValue()
            || spec.get("maxLength") instanceof Number max && length > max.intValue())
          throw new IllegalArgumentException("String outside schema bounds");
      }
      if (value instanceof Number number) {
        double n = number.doubleValue();
        if (spec.get("minimum") instanceof Number min && n < min.doubleValue()
            || spec.get("maximum") instanceof Number max && n > max.doubleValue())
          throw new IllegalArgumentException("Number outside schema bounds");
      }
    }
    return AgentJson.canonical(args);
  }
}
