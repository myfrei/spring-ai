package dev.myfrei.repopilot.auditexport;

import dev.myfrei.repopilot.capstone.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.verification.*;
import java.util.*;
import java.util.function.Function;

/** Extracts typed metadata; never serializes a raw capstone report or proof object. */
public class AuditExportChecker {
  private final Function<UUID, CapstoneReport> reports;
  private final Function<UUID, VerificationReport> verifications;
  private final Function<UUID, PatchProposal> patches;
  private final PortableAuditPolicy policy;

  public AuditExportChecker(
      CapstoneService capstone,
      VerificationService verification,
      ControlledService controlled,
      PortableAuditPolicy policy) {
    this(capstone::report, verification::report, controlled::proposal, policy);
  }

  AuditExportChecker(
      Function<UUID, CapstoneReport> reports,
      Function<UUID, VerificationReport> verifications,
      Function<UUID, PatchProposal> patches,
      PortableAuditPolicy policy) {
    this.reports = Objects.requireNonNull(reports);
    this.verifications = Objects.requireNonNull(verifications);
    this.patches = Objects.requireNonNull(patches);
    this.policy = Objects.requireNonNull(policy);
  }

  public PortableAuditPacket export(UUID sessionId, AuditExportRequest request) {
    var r = reports.apply(sessionId);
    if (!sessionId.equals(r.sessionId())) throw new AuditExportProblem("AUDIT_SCOPE_MISMATCH");
    var receipt = r.importReceipt();
    var provenance =
        receipt == null
            ? null
            : new PortableAuditInput.Provenance(
                receipt.serverName(),
                receipt.toolName(),
                receipt.protocolVersion(),
                receipt.initializeCalls(),
                receipt.listCalls(),
                receipt.toolCalls(),
                receipt.taskDigest());
    var gates =
        r.gates().stream().map(g -> new PortableAuditInput.Gate(g.code(), g.passed())).toList();
    var ids = new LinkedHashMap<String, UUID>();
    ids.put("session", sessionId);
    put(ids, "prepareJob", r.prepareJobId());
    put(ids, "reviewJob", r.reviewJobId());
    put(ids, "baselineReport", r.baselineReportId());
    put(ids, "passingReport", r.passingReportId());
    put(ids, "patch", r.patchId());
    PortableAuditInput.PatchEvidence patch = null;
    if (r.patchId() != null) {
      var p = patches.apply(r.patchId());
      patch =
          new PortableAuditInput.PatchEvidence(
              p.status(), p.baseHash(), p.resultHash(), p.operationHash());
    }
    return policy.export(
        new PortableAuditInput(
            r.taskId(),
            r.taskDigest(),
            r.state(),
            r.imageId(),
            provenance,
            gates,
            r.baselineRevision(),
            r.resultRevision(),
            r.currentRevision(),
            verification(r.baselineReportId()),
            verification(r.passingReportId()),
            patch,
            new PortableAuditInput.Details(r.relativeSourcePath(), ids),
            request.maxBytes()));
  }

  private PortableAuditInput.VerificationEvidence verification(UUID id) {
    if (id == null) return null;
    var v = verifications.apply(id);
    return new PortableAuditInput.VerificationEvidence(
        v.status(), v.exitCode(), v.inputRevision(), v.imageId(), v.inputsStillCurrent());
  }

  private static void put(Map<String, UUID> target, String key, UUID value) {
    if (value != null) target.put(key, value);
  }
}
