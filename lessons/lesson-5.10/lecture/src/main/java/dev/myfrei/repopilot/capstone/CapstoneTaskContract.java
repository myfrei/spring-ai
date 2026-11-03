package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.ingestion.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed teaching task contract, independent of the older fictional TaskShelf catalogue. */
public final class CapstoneTaskContract {
  public static final String TASK = "STOCK-001",
      TOOL = "getStockRoomTask",
      SERVER = "stockroom-capstone-tasks";
  private static final Map<String, Object> EXPECTED = load();

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load() {
    try (var input =
        CapstoneTaskContract.class.getResourceAsStream("/capstone/stockroom-task.json")) {
      if (input == null) throw new IllegalStateException("Pinned capstone task manifest required");
      return Map.copyOf(
          (Map<String, Object>)
              AgentJson.parse(new String(input.readNBytes(4097), StandardCharsets.UTF_8)));
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Pinned capstone task unavailable", failure);
    }
  }

  public static StockRoomTask decode(Object remote) {
    if (remote == null
        || AgentJson.bytes(AgentJson.encode(remote)) > 4096
        || !EXPECTED.equals(remote)) throw new CapstoneProblem("CAPSTONE_TASK_CONTRACT");
    return expected();
  }

  @SuppressWarnings("unchecked")
  public static StockRoomTask expected() {
    return new StockRoomTask(
        (String) EXPECTED.get("id"),
        (String) EXPECTED.get("title"),
        (String) EXPECTED.get("sourcePath"),
        (Integer) EXPECTED.get("startLine"),
        (Integer) EXPECTED.get("endLine"),
        (String) EXPECTED.get("command"),
        (String) EXPECTED.get("baselineHash"),
        (List<String>) EXPECTED.get("acceptance"),
        (String) EXPECTED.get("version"));
  }

  public static String digest(StockRoomTask task) {
    return Hashes.sha256(AgentJson.canonical(task.fields()));
  }

  private CapstoneTaskContract() {}
}
