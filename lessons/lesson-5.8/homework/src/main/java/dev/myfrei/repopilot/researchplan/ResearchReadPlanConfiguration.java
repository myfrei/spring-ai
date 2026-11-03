package dev.myfrei.repopilot.researchplan;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.research.ResearchRuntime;
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
public class ResearchReadPlanConfiguration {
  @Bean
  public ResearchCheckPlanPolicy researchCheckPlanPolicy() {
    return new ResearchCheckPlanPolicy();
  }

  @Bean
  public ResearchReadPlanChecker researchReadPlanChecker(
      ResearchRuntime runtime,
      AgentStore store,
      WorkspaceService workspace,
      ResearchCheckPlanPolicy policy) {
    return new ResearchReadPlanChecker(runtime, store, workspace, policy);
  }
}
