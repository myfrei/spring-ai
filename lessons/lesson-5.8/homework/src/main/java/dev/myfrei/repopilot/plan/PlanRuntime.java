package dev.myfrei.repopilot.plan;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.RunMode;
import dev.myfrei.repopilot.skills.*;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.util.*;

/**
 * Same SkillRuntime/ControlledService/AgentEngine loop, with plan hooks before prompt and commit.
 */
public final class PlanRuntime {
  public record PlanRunResult(
      AgentRun run, List<SkillSession.Activation> activations, PlanSnapshot plan) {
    public PlanRunResult {
      activations = List.copyOf(activations);
    }
  }

  private final SkillRuntime skills;
  private final WorkspaceService workspace;
  private final Map<UUID, PlanSession> sessions = new LinkedHashMap<>();
  private int admitted;

  public PlanRuntime(SkillRuntime skills, WorkspaceService workspace) {
    this.skills = Objects.requireNonNull(skills);
    this.workspace = Objects.requireNonNull(workspace);
  }

  public PlanRunResult run(
      UUID conversationId, String question, RunMode mode, List<String> requested, PlanSpec spec) {
    return run(conversationId, question, mode, requested, spec, null);
  }

  public PlanRunResult run(
      UUID conversationId,
      String question,
      RunMode mode,
      List<String> requested,
      PlanSpec spec,
      PromptProjection projection) {
    return run(conversationId, question, mode, requested, spec, projection, List.of());
  }

  public PlanRunResult run(
      UUID conversationId,
      String question,
      RunMode mode,
      List<String> requested,
      PlanSpec spec,
      PromptProjection projection,
      List<AgentTool> extras) {
    return run(
        conversationId,
        question,
        mode,
        requested,
        spec,
        projection,
        extras,
        AgentRunExtension.NONE);
  }

  public PlanRunResult run(
      UUID conversationId,
      String question,
      RunMode mode,
      List<String> requested,
      PlanSpec spec,
      PromptProjection projection,
      List<AgentTool> extras,
      AgentRunExtension extension) {
    Objects.requireNonNull(extension);
    new AgentRunRequest(question);
    Objects.requireNonNull(spec);
    var session = new PlanSession(spec, workspace);
    synchronized (sessions) {
      if (admitted >= 256) throw new PlanException("PLAN_CAPACITY");
      admitted++;
    }
    var registered = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      var tools = new ArrayList<>(session.tools());
      tools.addAll(List.copyOf(extras));
      tools.addAll(extension.tools());
      var result =
          skills.run(
              conversationId,
              question,
              mode,
              requested,
              tools,
              id -> {
                session.bind(id);
                synchronized (sessions) {
                  sessions.putIfAbsent(id, session);
                  registered.set(true);
                }
                return session.prompt(id) + "\n" + extension.context().apply(id);
              },
              id -> {
                String reason = session.completionReason(id);
                return reason == null ? extension.terminal().apply(id) : reason;
              },
              projection,
              extension.control());
      session.bind(result.run().id());
      synchronized (sessions) {
        sessions.putIfAbsent(result.run().id(), session);
        registered.set(true);
      }
      return new PlanRunResult(result.run(), result.activations(), session.view(result.run().id()));
    } finally {
      if (!registered.get())
        synchronized (sessions) {
          admitted--;
        }
    }
  }

  public PlanSnapshot snapshot(UUID runId) {
    PlanSession session;
    synchronized (sessions) {
      session = sessions.get(runId);
    }
    if (session == null) throw new PlanException("PLAN_RUN_NOT_FOUND");
    return session.view(runId);
  }
}
