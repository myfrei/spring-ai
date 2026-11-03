package dev.myfrei.repopilot.capstone;

import java.time.Instant;
import java.util.*;

/** Server-owned evidence summary. The diff is review data, not a portable/redacted export. */
public record CapstoneReport(
    UUID sessionId,
    String taskId,
    UUID conversationId,
    String state,
    String taskDigest,
    McpImportReceipt importReceipt,
    List<CapstoneGate> gates,
    UUID prepareJobId,
    UUID reviewJobId,
    UUID baselineReportId,
    UUID passingReportId,
    UUID patchId,
    String baselineRevision,
    String resultRevision,
    String currentRevision,
    String imageId,
    String relativeSourcePath,
    String diff,
    Instant generatedAt,
    CapstoneRunEvidence prepareEvidence,
    CapstoneRunEvidence reviewEvidence) {
  public CapstoneReport {
    gates = List.copyOf(gates);
  }
}
