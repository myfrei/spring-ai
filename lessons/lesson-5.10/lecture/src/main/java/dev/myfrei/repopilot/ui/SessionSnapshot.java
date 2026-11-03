package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.plan.PlanSnapshot;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.verification.VerificationService;
import java.util.*;

public record SessionSnapshot(
    UUID id,
    UUID conversationId,
    UUID requestId,
    String status,
    UUID runId,
    String stopReason,
    long lastSequence,
    long firstAvailableSequence,
    ResearchRuntime.ResearchRunResult result,
    List<ApprovalRef> approvals,
    PlanSnapshot plan,
    ResearchSnapshot research,
    VerificationService.VerificationState verification) {
  public SessionSnapshot {
    approvals = List.copyOf(approvals);
  }
}
