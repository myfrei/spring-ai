package dev.myfrei.repopilot.frontier;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

public record FrontierRequest(int maxSteps, int readLineBudget) {
  public FrontierRequest {
    if (maxSteps < 1 || maxSteps > 4 || readLineBudget < 1 || readLineBudget > 160)
      throw new IllegalArgumentException("Invalid frontier limits");
  }

  public static FrontierRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 256)
        throw new IllegalArgumentException("Frontier request too large");
      var args =
          AgentJson.arguments(
              raw, Set.of("maxSteps", "readLineBudget"), Set.of("maxSteps", "readLineBudget"));
      if (!(args.get("maxSteps") instanceof Integer max)
          || !(args.get("readLineBudget") instanceof Integer budget))
        throw new IllegalArgumentException("Integer limits required");
      return new FrontierRequest(max, budget);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid closed frontier JSON");
    }
  }
}
