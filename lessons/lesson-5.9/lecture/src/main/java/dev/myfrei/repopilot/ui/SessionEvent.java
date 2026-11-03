package dev.myfrei.repopilot.ui;

import java.time.Instant;
import java.util.Map;

public record SessionEvent(
    long sequence, String type, Map<String, Object> attributes, Instant occurredAt) {
  public SessionEvent {
    attributes = Map.copyOf(attributes);
  }
}
