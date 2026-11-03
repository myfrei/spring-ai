package dev.myfrei.repopilot.verification;

import dev.myfrei.repopilot.agent.AgentRun;
import dev.myfrei.repopilot.agent.AgentRunExtension;
import dev.myfrei.repopilot.compaction.*;
import dev.myfrei.repopilot.plan.PlanSnapshot;
import dev.myfrei.repopilot.skills.SkillSession;
import java.util.*;

public final class VerificationRuntime {
  public record VerifiedRunResult(
      AgentRun run,
      List<SkillSession.Activation> activations,
      PlanSnapshot plan,
      CompactionReport context,
      VerificationService.VerificationState verification) {
    public VerifiedRunResult {
      activations = List.copyOf(activations);
    }
  }

  private final ContextRuntime contexts;
  private final VerificationService service;

  public VerificationRuntime(ContextRuntime contexts, VerificationService service) {
    this.contexts = contexts;
    this.service = service;
  }

  public VerifiedRunResult run(UUID conversation, ContextRequest request) {
    return run(conversation, request, AgentRunExtension.NONE);
  }

  public VerifiedRunResult run(
      UUID conversation, ContextRequest request, AgentRunExtension extension) {
    var result = contexts.run(conversation, request, service.reportTools(), extension);
    return new VerifiedRunResult(
        result.run(),
        result.activations(),
        result.plan(),
        result.context(),
        service.state(conversation));
  }
}
