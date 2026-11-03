package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.research.ResearchRequest;
import java.util.*;

public record SessionRequest(UUID requestId, ResearchRequest task) {
  public SessionRequest {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(task);
  }

  public static SessionRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 12288) throw new IllegalArgumentException();
      var a = AgentJson.arguments(raw, Set.of("requestId", "task"), Set.of("requestId", "task"));
      if (!(a.get("requestId") instanceof String text)
          || !UUID.fromString(text).toString().equals(text)
          || !(a.get("task") instanceof Map<?, ?>)) throw new IllegalArgumentException();
      return new SessionRequest(
          UUID.fromString(text), ResearchRequest.decode(AgentJson.encode(a.get("task"))));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Closed asynchronous task required");
    }
  }
}
