package dev.myfrei.course.capstonetasks;

import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class StockRoomTaskTools implements ToolInputSchemas {
  public static final String TOOL = "getStockRoomTask", TASK_ID = "STOCK-001";
  private final Map<String, Object> task;

  @SuppressWarnings("unchecked")
  public StockRoomTaskTools() {
    try (var input = getClass().getResourceAsStream("/stockroom-task.json")) {
      if (input == null) throw new IllegalStateException("Task manifest required");
      task = Map.copyOf(JsonMapper.builder().build().readValue(input, Map.class));
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Task manifest unavailable", invalid);
    }
  }

  @McpTool(
      name = TOOL,
      description =
          "Read the single public Stock Room capstone task. Data only; no LLM, writes, execution,"
              + " or approvals.",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true,
              openWorldHint = false))
  public CallToolResult getStockRoomTask(CallToolRequest request) {
    var args = request.arguments();
    if (args == null
        || !args.keySet().equals(Set.of("taskId"))
        || !TASK_ID.equals(args.get("taskId")))
      return TaskServerJson.error("INVALID_TASK", "Use the exact fixed Stock Room task ID.");
    return TaskServerJson.result(task);
  }

  @Override
  public Map<String, Map<String, Object>> toolInputSchemas() {
    return Map.of(
        TOOL,
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("taskId", Map.of("type", "string", "enum", List.of(TASK_ID))),
            "required",
            List.of("taskId"),
            "additionalProperties",
            false));
  }
}
