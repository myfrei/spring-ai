package dev.myfrei.repopilot.capstone;

public interface CapstoneTaskGateway {
  record ImportedTask(StockRoomTask task, McpImportReceipt receipt) {}

  ImportedTask importTask(String taskId);
}
