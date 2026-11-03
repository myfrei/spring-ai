package dev.myfrei.repopilot.agent;

import java.util.*;

public record AgentConversation(UUID id, List<AgentTurn> turns, UUID activeRunId) {
  public AgentConversation {
    turns = List.copyOf(turns);
  }
}
