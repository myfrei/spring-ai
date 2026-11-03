package dev.myfrei.repopilot.auditexport;

import java.util.Objects;

/** Builds an unsigned portable snapshot, never grants DONE or reruns a verification. */
public class PortableAuditPolicy {
  public PortableAuditPacket export(PortableAuditInput input) {
    Objects.requireNonNull(input);
    // TODO: allowlisted canonical packet, payload checksum and complete-document byte budget.
    throw new UnsupportedOperationException("Implement PortableAuditPolicy.export");
  }
}
