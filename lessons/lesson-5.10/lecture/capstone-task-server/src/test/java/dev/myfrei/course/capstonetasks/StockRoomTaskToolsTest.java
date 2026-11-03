package dev.myfrei.course.capstonetasks;

import static org.assertj.core.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import org.junit.jupiter.api.Test;

class StockRoomTaskToolsTest {
  private final StockRoomTaskTools tools = new StockRoomTaskTools();

  @Test
  void returnsOnlyFixedStockRoomTaskAndIndependentDomain() {
    var result =
        tools.getStockRoomTask(
            McpSchema.CallToolRequest.builder(StockRoomTaskTools.TOOL)
                .arguments(Map.of("taskId", "STOCK-001"))
                .build());
    assertThat(result.isError()).isFalse();
    assertThat(TaskServerJson.encode(result.structuredContent()))
        .contains("STOCKROOM_CONTRACT_V1", "StockLedger.java", "baselineHash")
        .doesNotContain("TASK-101", "TaskShelf", "approval");
  }

  @Test
  void rawUnknownNullAndWrongTaskIdsCannotSelectAnotherOperation() {
    var invalid = new ArrayList<Map<String, Object>>();
    invalid.add(Map.of());
    invalid.add(Map.of("taskId", "TASK-101"));
    invalid.add(Map.of("taskId", "STOCK-001", "command", "shell"));
    var nil = new HashMap<String, Object>();
    nil.put("taskId", null);
    invalid.add(nil);
    for (var args : invalid)
      assertThat(
              tools
                  .getStockRoomTask(
                      McpSchema.CallToolRequest.builder(StockRoomTaskTools.TOOL)
                          .arguments(args)
                          .build())
                  .isError())
          .isTrue();
  }
}
