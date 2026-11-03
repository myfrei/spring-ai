package dev.myfrei.repopilot.compaction;

import java.util.*;

/** The source transcript is AgentStore.messages(runId), never this lossy projection report. */
public record CompactionReport(UUID runId, List<ProjectionFrame> frames) {
  public CompactionReport {
    Objects.requireNonNull(runId);
    frames = List.copyOf(frames);
  }

  public List<String> omittedToolCallIds() {
    return frames.stream()
        .flatMap(frame -> frame.omittedCurrentCallIds().stream())
        .distinct()
        .toList();
  }
}
