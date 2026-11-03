package dev.myfrei.repopilot.agent;

import java.util.List;

@FunctionalInterface
public interface RunStopRule {
  boolean afterTool(List<ToolObservation> history);
}
