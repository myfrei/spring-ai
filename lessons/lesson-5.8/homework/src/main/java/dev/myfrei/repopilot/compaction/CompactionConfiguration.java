package dev.myfrei.repopilot.compaction;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.plan.PlanRuntime;
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
public class CompactionConfiguration {
  @Bean
  public ContextRuntime contextRuntime(PlanRuntime plans, AgentStore store) {
    return new ContextRuntime(plans, store);
  }
}
