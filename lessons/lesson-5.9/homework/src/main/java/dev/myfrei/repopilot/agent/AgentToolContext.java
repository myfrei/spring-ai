package dev.myfrei.repopilot.agent;

import java.util.*;

public record AgentToolContext(UUID runId, UUID conversationId) {
  public AgentToolContext {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(conversationId);
  }
}
