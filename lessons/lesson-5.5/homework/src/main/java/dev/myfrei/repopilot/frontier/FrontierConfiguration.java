package dev.myfrei.repopilot.frontier;

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
      "repopilot.plans.enabled"
    },
    havingValue = "true")
public class FrontierConfiguration {
  @Bean
  public PlanFrontierPolicy planFrontierPolicy() {
    return new PlanFrontierPolicy();
  }

  @Bean
  public FrontierChecker frontierChecker(PlanRuntime runtime, PlanFrontierPolicy policy) {
    return new FrontierChecker(runtime, policy);
  }
}
