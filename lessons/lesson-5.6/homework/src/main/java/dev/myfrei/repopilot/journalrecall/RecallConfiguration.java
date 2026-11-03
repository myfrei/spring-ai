package dev.myfrei.repopilot.journalrecall;

import dev.myfrei.repopilot.agent.AgentStore;
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
      "repopilot.compaction.enabled"
    },
    havingValue = "true")
public class RecallConfiguration {
  @Bean
  public ToolRecallPolicy toolRecallPolicy() {
    return new ToolRecallPolicy();
  }

  @Bean
  public RecallChecker recallChecker(
      AgentStore store, WorkspaceService workspace, ToolRecallPolicy policy) {
    return new RecallChecker(store, workspace, policy);
  }
}
