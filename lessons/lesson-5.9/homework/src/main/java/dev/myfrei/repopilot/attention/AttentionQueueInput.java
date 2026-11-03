package dev.myfrei.repopilot.attention;

import java.util.*;

/** Only bounded server metadata and caller-owned display acknowledgements. */
public record AttentionQueueInput(UUID conversationId, List<Session> sessions, int maxItems) {
  public AttentionQueueInput {
    Objects.requireNonNull(conversationId);
    if (sessions == null
        || sessions.size() > 64
        || sessions.stream().anyMatch(Objects::isNull)
        || maxItems < 1
        || maxItems > 12) throw new IllegalArgumentException("Invalid attention bounds");
    sessions = List.copyOf(sessions);
    var high = new HashMap<UUID, Long>();
    sessions.forEach(s -> high.merge(s.id(), s.lastSequence(), Math::max));
    for (var s : sessions)
      if (s.acknowledgedThrough() > high.get(s.id()))
        throw new IllegalArgumentException("Acknowledgement exceeds server high watermark");
  }

  public record Session(
      UUID id,
      String status,
      String stopReason,
      long lastSequence,
      long acknowledgedThrough,
      List<Approval> approvals) {
    public Session {
      Objects.requireNonNull(id);
      if (status == null
          || !Set.of("QUEUED", "RUNNING", "COMPLETED", "STOPPED", "FAILED").contains(status)
          || lastSequence < 0
          || acknowledgedThrough < -1
          || approvals == null
          || approvals.size() > 256
          || approvals.stream().anyMatch(Objects::isNull))
        throw new IllegalArgumentException("Invalid session metadata");
      if (stopReason == null) stopReason = "";
      if (!stopReason.isEmpty() && !stopReason.matches("[A-Z][A-Z0-9_]{0,95}"))
        throw new IllegalArgumentException("Expected a coded stop reason");
      approvals = List.copyOf(approvals);
    }
  }

  public record Approval(String kind, UUID id, String status) {
    public Approval {
      Objects.requireNonNull(id);
      if (kind == null
          || !Set.of("PATCH", "VERIFICATION").contains(kind)
          || status == null
          || !status.matches("[A-Z][A-Z0-9_]{0,63}"))
        throw new IllegalArgumentException("Invalid approval metadata");
    }
  }
}
