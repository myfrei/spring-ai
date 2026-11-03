package dev.myfrei.repopilot.auditexport;

import dev.myfrei.repopilot.capstone.CapstoneService;
import dev.myfrei.repopilot.controlled.ControlledService;
import dev.myfrei.repopilot.verification.VerificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
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
public class AuditExportConfiguration {
  @Bean
  public PortableAuditPolicy portableAuditPolicy() {
    return new PortableAuditPolicy();
  }

  @Bean
  public AuditExportChecker auditExportChecker(
      CapstoneService capstone,
      VerificationService verification,
      ControlledService controlled,
      PortableAuditPolicy policy) {
    return new AuditExportChecker(capstone, verification, controlled, policy);
  }
}
