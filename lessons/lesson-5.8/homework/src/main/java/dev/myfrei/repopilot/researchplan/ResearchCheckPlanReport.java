package dev.myfrei.repopilot.researchplan;

import java.util.*;

public record ResearchCheckPlanReport(
    UUID parentRunId,
    List<FindingCheck> findings,
    List<ReadTask> reads,
    int requiredLines,
    int plannedLines,
    int deferredLines,
    boolean complete) {
  public ResearchCheckPlanReport {
    findings = List.copyOf(findings);
    reads = List.copyOf(reads);
    if (reads.size() > 16) throw new ResearchReadPlanProblem("READ_PLAN_TASK_LIMIT");
  }

  public record FindingCheck(
      int index,
      String path,
      String fullFileHash,
      int startLine,
      int endLine,
      String status,
      int missingLines) {}

  public record ReadTask(String path, String fullFileHash, int startLine, int endLine) {
    public ReadTask {
      ResearchCheckPlanInput.validatePath(path);
      ResearchCheckPlanInput.validateHash(fullFileHash);
      ResearchCheckPlanInput.validateRange(startLine, endLine);
    }
  }
}
