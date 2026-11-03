package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.AgentRunReport;
import dev.myfrei.repopilot.plan.PlanSnapshot;
import dev.myfrei.repopilot.research.ResearchBudgetSnapshot;
import dev.myfrei.repopilot.skills.SkillSession;
import dev.myfrei.repopilot.workspace.ReadReceipt;
import java.util.*;

/**
 * Typed metadata only; original questions, instructions and tool payloads stay in their journals.
 */
public record CapstoneRunEvidence(
    UUID jobId,
    UUID runId,
    String runStatus,
    String stopReason,
    PlanSnapshot plan,
    List<SkillSession.Activation> skills,
    List<ReadReceipt> reads,
    AgentRunReport runReport,
    ResearchBudgetSnapshot admission) {
  public CapstoneRunEvidence {
    skills = List.copyOf(skills);
    reads = List.copyOf(reads);
  }
}
