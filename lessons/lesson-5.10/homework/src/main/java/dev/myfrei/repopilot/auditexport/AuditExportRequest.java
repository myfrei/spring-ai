package dev.myfrei.repopilot.auditexport;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.Set;

public record AuditExportRequest(int maxBytes) {
  public AuditExportRequest {
    if (maxBytes < 1 || maxBytes > 16384)
      throw new IllegalArgumentException("Expected maxBytes 1–16384");
  }

  public static AuditExportRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 128)
        throw new IllegalArgumentException("Export request too large");
      var args = AgentJson.arguments(raw, Set.of("maxBytes"), Set.of("maxBytes"));
      if (!(args.get("maxBytes") instanceof Integer bytes))
        throw new IllegalArgumentException("Integer maxBytes required");
      return new AuditExportRequest(bytes);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid closed audit-export JSON");
    }
  }
}
