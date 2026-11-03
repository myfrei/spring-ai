package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

public record SessionCommand(String type, UUID approvalId) {
  public SessionCommand {
    if (!Set.of(
            "CANCEL",
            "REQUEST_VERIFICATION",
            "APPROVE_PATCH",
            "REJECT_PATCH",
            "APPROVE_VERIFICATION",
            "REJECT_VERIFICATION")
        .contains(type == null ? "" : type))
      throw new IllegalArgumentException("Unknown session command");
    boolean needs = type.startsWith("APPROVE_") || type.startsWith("REJECT_");
    if (needs != (approvalId != null))
      throw new IllegalArgumentException("Exact approval identifier required");
  }

  public static SessionCommand decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 512) throw new IllegalArgumentException();
      var a = AgentJson.arguments(raw, Set.of("type", "approvalId"), Set.of("type"));
      if (!(a.get("type") instanceof String type)) throw new IllegalArgumentException();
      UUID id = null;
      if (a.containsKey("approvalId")) {
        if (!(a.get("approvalId") instanceof String text)
            || !UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException();
        id = UUID.fromString(text);
      }
      return new SessionCommand(type, id);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Closed session command required");
    }
  }
}
