package dev.myfrei.course.capstonetasks;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Stable, bounded result encoding shared by server tools and optional extensions. */
public final class TaskServerJson {
  public static final int MAX_OUTPUT_BYTES = 4096;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private TaskServerJson() {}

  public static JacksonMcpJsonMapper mapper() {
    return new JacksonMcpJsonMapper(JSON);
  }

  public static String encode(Object value) {
    return JSON.writeValueAsString(value);
  }

  public static String boundedText(String text) {
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES)
      throw new IllegalArgumentException("Output limit exceeded");
    return text;
  }

  public static McpSchema.CallToolResult result(Object value) {
    return result(value, false);
  }

  public static McpSchema.CallToolResult error(String code, String message) {
    return result(Map.of("code", code, "message", message), true);
  }

  private static McpSchema.CallToolResult result(Object value, boolean error) {
    String json = encode(value);
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES)
      return error("OUTPUT_TOO_LARGE", "Результат не помещается в ограниченный ответ.");
    return McpSchema.CallToolResult.builder(List.of(McpSchema.TextContent.builder(json).build()))
        .structuredContent(value)
        .isError(error)
        .build();
  }
}
