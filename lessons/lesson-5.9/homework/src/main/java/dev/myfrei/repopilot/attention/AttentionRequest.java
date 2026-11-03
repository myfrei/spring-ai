package dev.myfrei.repopilot.attention;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

public record AttentionRequest(int maxItems, Map<UUID, Long> acknowledgedThrough) {
  public AttentionRequest {
    if (maxItems < 1
        || maxItems > 12
        || acknowledgedThrough == null
        || acknowledgedThrough.size() > 64
        || acknowledgedThrough.entrySet().stream()
            .anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 0))
      throw new IllegalArgumentException("Invalid attention request");
    acknowledgedThrough = Map.copyOf(acknowledgedThrough);
  }

  public static AttentionRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 8192)
        throw new IllegalArgumentException("Attention JSON too large");
      var args =
          AgentJson.arguments(raw, Set.of("maxItems", "acknowledgedThrough"), Set.of("maxItems"));
      if (!(args.get("maxItems") instanceof Integer limit))
        throw new IllegalArgumentException("Integer maxItems required");
      var acknowledgements = new HashMap<UUID, Long>();
      if (args.containsKey("acknowledgedThrough")) {
        if (!(args.get("acknowledgedThrough") instanceof Map<?, ?> map) || map.size() > 64)
          throw new IllegalArgumentException("Expected bounded acknowledgement map");
        for (var entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String id)
              || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
              || !(entry.getValue() instanceof Number n)
              || !(n instanceof Integer || n instanceof Long)
              || n.longValue() < 0)
            throw new IllegalArgumentException(
                "Canonical session UUID and nonnegative integer sequence required");
          acknowledgements.put(UUID.fromString(id), n.longValue());
        }
      }
      return new AttentionRequest(limit, acknowledgements);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid closed attention JSON");
    }
  }
}
