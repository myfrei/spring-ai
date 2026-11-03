package dev.myfrei.repopilot.agent;

import java.util.UUID;

/** Trusted cooperative admission hooks. Null allows; a stable reason stops the existing loop. */
public interface AgentRunControl {
  AgentRunControl NONE = new AgentRunControl() {};

  default String check(UUID runId) {
    return null;
  }

  default String beforeModel(UUID runId, int estimatedInputTokens, int outputReserve) {
    return check(runId);
  }

  default void afterModel(
      UUID runId, Integer inputTokens, Integer outputTokens, boolean usageKnown) {}

  default String beforeTool(UUID runId, String name) {
    return check(runId);
  }
}
