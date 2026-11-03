package dev.myfrei.repopilot.journalrecall;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** A caller supplies only identifiers and a byte limit, never a journal or current hashes. */
public record RecallRequest(List<String> callIds, int payloadBudgetBytes) {
  public RecallRequest {
    if (callIds == null
        || callIds.isEmpty()
        || callIds.size() > 8
        || payloadBudgetBytes < 1
        || payloadBudgetBytes > 4096)
      throw new IllegalArgumentException("Invalid recall request bounds");
    callIds.forEach(RecallInput::validateCallId);
    callIds = List.copyOf(callIds);
  }

  public static RecallRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 4096)
        throw new IllegalArgumentException("Recall request too large");
      var args =
          AgentJson.arguments(
              raw,
              Set.of("callIds", "payloadBudgetBytes"),
              Set.of("callIds", "payloadBudgetBytes"));
      if (!(args.get("callIds") instanceof List<?> list)
          || !(args.get("payloadBudgetBytes") instanceof Integer budget))
        throw new IllegalArgumentException("Expected identifiers and integer budget");
      var ids = new ArrayList<String>();
      for (var id : list) {
        if (!(id instanceof String text))
          throw new IllegalArgumentException("Expected string tool call identifier");
        ids.add(text);
      }
      return new RecallRequest(ids, budget);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid closed recall JSON");
    }
  }
}
