package dev.myfrei.repopilot.compaction;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.plan.PlanRequest;
import java.util.*;

/**
 * Reuses the original closed plan contract; the only new input is an explicit admission ceiling.
 */
public record ContextRequest(PlanRequest task, int maxInputTokens) {
  public ContextRequest {
    Objects.requireNonNull(task);
    if (maxInputTokens < 1 || maxInputTokens > 32768)
      throw new IllegalArgumentException("Input budget1..32768 required");
  }

  public static ContextRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 8192)
        throw new IllegalArgumentException("Bounded request");
      var fields =
          AgentJson.arguments(
              raw,
              Set.of("question", "mode", "skills", "steps", "maxInputTokens"),
              Set.of("question", "mode", "steps", "maxInputTokens"));
      if (!(fields.get("maxInputTokens") instanceof Integer target))
        throw new IllegalArgumentException("Integer budget required");
      var plan = new LinkedHashMap<>(fields);
      plan.remove("maxInputTokens");
      return new ContextRequest(PlanRequest.decode(AgentJson.encode(plan)), target);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid context JSON");
    }
  }
}
