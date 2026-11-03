package dev.myfrei.repopilot.researchplan;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.Set;

public record ResearchReadPlanRequest(int readLineBudget) {
  public ResearchReadPlanRequest {
    if (readLineBudget < 1 || readLineBudget > 160)
      throw new IllegalArgumentException("Expected readLineBudget 1–160");
  }

  public static ResearchReadPlanRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 128)
        throw new IllegalArgumentException("Read-plan JSON too large");
      var args = AgentJson.arguments(raw, Set.of("readLineBudget"), Set.of("readLineBudget"));
      if (!(args.get("readLineBudget") instanceof Integer budget))
        throw new IllegalArgumentException("Integer readLineBudget required");
      return new ResearchReadPlanRequest(budget);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid closed read-plan JSON");
    }
  }
}
