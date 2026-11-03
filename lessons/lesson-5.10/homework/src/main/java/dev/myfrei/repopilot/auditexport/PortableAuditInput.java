package dev.myfrei.repopilot.auditexport;

import java.util.*;

/** Safe structured server facts only. No diff, logs, questions, tokens, URLs or timestamps. */
public record PortableAuditInput(
    String taskId,
    String taskDigest,
    String state,
    String imageId,
    Provenance provenance,
    List<Gate> gates,
    String baselineRevision,
    String resultRevision,
    String currentRevision,
    VerificationEvidence baseline,
    VerificationEvidence finalVerification,
    PatchEvidence patch,
    Details details,
    int maxBytes) {
  public PortableAuditInput {
    if (maxBytes < 1 || maxBytes > 16384 || gates != null && gates.size() > 32)
      throw new IllegalArgumentException("Invalid export bounds");
    if (gates != null) gates = Collections.unmodifiableList(new ArrayList<>(gates));
  }

  public record Provenance(
      String serverName,
      String toolName,
      String protocolVersion,
      int initializeCalls,
      int listCalls,
      int toolCalls,
      String taskDigest) {}

  public record Gate(String code, boolean passed) {}

  public record VerificationEvidence(
      String status,
      Integer exitCode,
      String inputRevision,
      String imageId,
      boolean inputsStillCurrent) {}

  public record PatchEvidence(
      String status, String baseHash, String resultHash, String operationHash) {}

  public record Details(String relativeSourcePath, Map<String, UUID> evidenceIds) {
    public Details {
      if (evidenceIds != null) {
        if (evidenceIds.size() > 16) throw new IllegalArgumentException("Evidence ID cap exceeded");
        evidenceIds = Collections.unmodifiableMap(new LinkedHashMap<>(evidenceIds));
      }
    }
  }
}
