package dev.myfrei.repopilot.stability;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** A caller submits report IDs only; report metadata and output are never accepted. */
public record StabilityRequest(List<UUID> reportIds) {
  public StabilityRequest {
    if (reportIds == null
        || reportIds.isEmpty()
        || reportIds.size() > 8
        || reportIds.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Expected 1–8 reportIds");
    reportIds = List.copyOf(reportIds);
  }

  public static StabilityRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 1024)
        throw new IllegalArgumentException("Stability JSON too large");
      var args = AgentJson.arguments(raw, Set.of("reportIds"), Set.of("reportIds"));
      if (!(args.get("reportIds") instanceof List<?> list) || list.isEmpty() || list.size() > 8)
        throw new IllegalArgumentException("Expected reportIds");
      var ids = new ArrayList<UUID>();
      for (var item : list) {
        if (!(item instanceof String text)
            || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
          throw new IllegalArgumentException("Canonical UUID required");
        ids.add(UUID.fromString(text));
      }
      return new StabilityRequest(ids);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid closed stability JSON");
    }
  }
}
