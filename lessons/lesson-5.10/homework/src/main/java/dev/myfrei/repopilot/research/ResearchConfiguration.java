package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.verification.VerificationRuntime;
import dev.myfrei.repopilot.workspace.WorkspaceService;
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
      "repopilot.research.enabled"
    },
    havingValue = "true")
public class ResearchConfiguration {
  @Bean
  public ResearchRuntime researchRuntime(
      VerificationRuntime verification,
      AgentEngine base,
      AgentStore store,
      WorkspaceService workspace) {
    return new ResearchRuntime(verification, base, store, workspace);
  }
}
