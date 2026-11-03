package dev.myfrei.repopilot.progress;

import java.util.List;
import dev.myfrei.repopilot.agent.RunStopRule;
import dev.myfrei.repopilot.agent.ToolObservation;

/** Homework extension: inspect completed tool observations, never execute or retry a tool. */
public class CycleStopRule implements RunStopRule {
    @Override public boolean afterTool(List<ToolObservation> observations){
        if(observations==null||observations.size()>12||observations.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Expected at most12 nonnull tool observations");
        return repeatedSuffix(List.copyOf(observations));
    }
    private boolean repeatedSuffix(List<ToolObservation> observations){
        // TODO HW5.1: two equal adjacent suffix blocks, each of period1..3.
        throw new UnsupportedOperationException("HW5.1: detect repeated tool observations");
    }
}
