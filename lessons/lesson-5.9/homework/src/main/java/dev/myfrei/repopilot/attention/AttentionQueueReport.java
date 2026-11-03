package dev.myfrei.repopilot.attention;

import java.util.*;

public record AttentionQueueReport(
    UUID conversationId,
    List<Notice> notices,
    int eligibleCount,
    int blockingCount,
    int omittedCount,
    int omittedBlockingCount,
    boolean allActionableVisible,
    Map<UUID, Long> highWatermarks) {
  public AttentionQueueReport {
    notices = List.copyOf(notices);
    highWatermarks = Map.copyOf(highWatermarks);
  }

  public record Notice(
      String key,
      UUID sessionId,
      long sequence,
      String kind,
      String approvalKind,
      UUID approvalId,
      String code,
      boolean blocking) {}
}
