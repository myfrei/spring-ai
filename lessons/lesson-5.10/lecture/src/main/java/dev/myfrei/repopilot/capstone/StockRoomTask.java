package dev.myfrei.repopilot.capstone;

import java.util.*;

public record StockRoomTask(
    String id,
    String title,
    String sourcePath,
    int startLine,
    int endLine,
    String command,
    String baselineHash,
    List<String> acceptance,
    String version) {
  public StockRoomTask {
    acceptance = List.copyOf(acceptance);
  }

  public Map<String, Object> fields() {
    return Map.of(
        "id",
        id,
        "title",
        title,
        "sourcePath",
        sourcePath,
        "startLine",
        startLine,
        "endLine",
        endLine,
        "command",
        command,
        "baselineHash",
        baselineHash,
        "acceptance",
        acceptance,
        "version",
        version);
  }
}
