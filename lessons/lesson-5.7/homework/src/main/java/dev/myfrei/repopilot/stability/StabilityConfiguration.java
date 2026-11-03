package dev.myfrei.repopilot.stability;

import dev.myfrei.repopilot.agent.AgentStore;
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
      "repopilot.verification.enabled"
    },
    havingValue = "true")
public class StabilityConfiguration {
  @Bean
  public VerificationStabilityPolicy verificationStabilityPolicy() {
    return new VerificationStabilityPolicy();
  }

  @Bean
  public VerificationStabilityChecker verificationStabilityChecker(
      AgentStore store, VerificationService service, VerificationStabilityPolicy policy) {
    return new VerificationStabilityChecker(store, service, policy);
  }
}
