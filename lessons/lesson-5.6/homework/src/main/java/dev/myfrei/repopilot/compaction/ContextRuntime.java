package dev.myfrei.repopilot.compaction;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.skills.SkillSession;
import java.util.*;

/** Opt-in projection over the same planned runtime. The original routes and journal stay intact. */
public final class ContextRuntime {
  public record ContextRunResult(
      AgentRun run,
      List<SkillSession.Activation> activations,
      PlanSnapshot plan,
      CompactionReport context) {
    public ContextRunResult {
      activations = List.copyOf(activations);
    }
  }

  private final PlanRuntime plans;
  private final AgentStore store;
  private final Map<UUID, CompactionReport> reports = new LinkedHashMap<>();

  public ContextRuntime(PlanRuntime plans, AgentStore store) {
    this.plans = Objects.requireNonNull(plans);
    this.store = Objects.requireNonNull(store);
  }

  public ContextRunResult run(UUID conversationId, ContextRequest request) {
    Objects.requireNonNull(request);
    var task = request.task();
    var projection = new CompactingProjection(request.maxInputTokens());
    var result =
        plans.run(
            conversationId, task.question(), task.mode(), task.skills(), task.plan(), projection);
    var report = projection.report(result.run().id());
    synchronized (reports) {
      reports.put(result.run().id(), report);
      while (reports.size() > 256) reports.remove(reports.keySet().iterator().next());
    }
    return new ContextRunResult(result.run(), result.activations(), result.plan(), report);
  }

  public CompactionReport report(UUID runId) {
    store.run(runId);
    synchronized (reports) {
      var report = reports.get(runId);
      if (report == null) throw new CompactionProblem("COMPACTION_REPORT_NOT_FOUND");
      return report;
    }
  }
}
