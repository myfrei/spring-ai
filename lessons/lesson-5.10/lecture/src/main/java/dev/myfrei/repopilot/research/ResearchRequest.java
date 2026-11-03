package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.compaction.ContextRequest;
import java.util.*;

/**
 * The caller controls bounded budgets, not child permissions, depth, evidence, or successful
 * status.
 */
public record ResearchRequest(ContextRequest task, ResearchLimits budget) {
  public ResearchRequest {
    Objects.requireNonNull(task);
    Objects.requireNonNull(budget);
  }

  public static ResearchRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 8192)
        throw new IllegalArgumentException("Bounded research request");
      var fields =
          AgentJson.arguments(
              raw,
              Set.of("question", "mode", "skills", "steps", "maxInputTokens", "researchBudget"),
              Set.of("question", "mode", "steps", "maxInputTokens"));
      ResearchLimits limits = ResearchLimits.DEFAULT;
      if (fields.containsKey("researchBudget")) {
        if (!(fields.get("researchBudget") instanceof Map<?, ?> map)
            || !map.keySet()
                .equals(Set.of("modelCalls", "toolRequests", "estimatedTokenReservations"))
            || !(map.get("modelCalls") instanceof Integer models)
            || !(map.get("toolRequests") instanceof Integer tools)
            || !(map.get("estimatedTokenReservations") instanceof Integer tokens))
          throw new IllegalArgumentException("Closed research budget");
        limits = new ResearchLimits(models, tools, tokens);
      }
      var context = new LinkedHashMap<>(fields);
      context.remove("researchBudget");
      return new ResearchRequest(ContextRequest.decode(AgentJson.encode(context)), limits);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid research JSON");
    }
  }
}
