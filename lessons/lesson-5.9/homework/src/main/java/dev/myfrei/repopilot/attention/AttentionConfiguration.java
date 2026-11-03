package dev.myfrei.repopilot.attention;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.ui.SessionCoordinator;
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
      "repopilot.sessions.enabled"
    },
    havingValue = "true")
public class AttentionConfiguration {
  @Bean
  public AttentionQueuePolicy attentionQueuePolicy() {
    return new AttentionQueuePolicy();
  }

  @Bean
  public AttentionQueueChecker attentionQueueChecker(
      AgentStore store, SessionCoordinator coordinator, AttentionQueuePolicy policy) {
    return new AttentionQueueChecker(store, coordinator, policy);
  }
}
