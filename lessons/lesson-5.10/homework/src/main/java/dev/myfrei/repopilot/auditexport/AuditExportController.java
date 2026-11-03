package dev.myfrei.repopilot.auditexport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/capstone/sessions")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled",
      "repopilot.plans.enabled",
      "repopilot.compaction.enabled",
      "repopilot.verification.enabled",
      "repopilot.research.enabled",
      "repopilot.sessions.enabled",
      "repopilot.capstone.enabled"
    },
    havingValue = "true")
public class AuditExportController {
  private final AuditExportChecker checker;

  public AuditExportController(AuditExportChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/{id}/audit/export")
  public ResponseEntity<String> export(@PathVariable UUID id, @RequestBody String raw) {
    var packet = checker.export(id, AuditExportRequest.decode(raw));
    return ResponseEntity.ok()
        .contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("repopilot-audit.json").build().toString())
        .header("X-Audit-SHA256", packet.sha256())
        .body(packet.json());
  }
}
