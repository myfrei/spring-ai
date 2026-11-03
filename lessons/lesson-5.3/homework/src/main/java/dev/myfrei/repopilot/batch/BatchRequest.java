package dev.myfrei.repopilot.batch;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** Closed input: proposal identifiers only, no caller-owned status or hashes. */
public record BatchRequest(List<UUID> proposalIds) {
  public BatchRequest {
    if (proposalIds == null
        || proposalIds.isEmpty()
        || proposalIds.size() > 12
        || proposalIds.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Expected1–12 proposalIds");
    proposalIds = List.copyOf(proposalIds);
  }

  public static BatchRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 2048)
        throw new IllegalArgumentException("Batch request too large");
      var args = AgentJson.arguments(raw, Set.of("proposalIds"), Set.of("proposalIds"));
      if (!(args.get("proposalIds") instanceof List<?> list) || list.isEmpty() || list.size() > 12)
        throw new IllegalArgumentException("Expected proposalIds");
      var ids = new ArrayList<UUID>();
      for (var value : list) {
        if (!(value instanceof String text)
            || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
          throw new IllegalArgumentException("Canonical UUID required");
        ids.add(UUID.fromString(text));
      }
      return new BatchRequest(ids);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid batch JSON");
    }
  }
}
