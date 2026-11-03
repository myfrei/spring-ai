package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.controlled.ControlledService;
import dev.myfrei.repopilot.plan.PlanRuntime;
import dev.myfrei.repopilot.research.ResearchRuntime;
import dev.myfrei.repopilot.skills.SkillRuntime;
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
      "repopilot.sessions.enabled"
    },
    havingValue = "true")
public class SessionConfiguration {
  @Bean
  public SessionCoordinator sessionCoordinator(
      ResearchRuntime research,
      AgentStore store,
      PlanRuntime plans,
      SkillRuntime skills,
      ControlledService controlled,
      VerificationService verification) {
    return new SessionCoordinator(research, store, plans, skills, controlled, verification);
  }
}
