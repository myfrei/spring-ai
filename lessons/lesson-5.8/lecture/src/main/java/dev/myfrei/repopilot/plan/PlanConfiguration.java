package dev.myfrei.repopilot.plan;

import dev.myfrei.repopilot.skills.SkillRuntime;
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
      "repopilot.plans.enabled"
    },
    havingValue = "true")
public class PlanConfiguration {
  @Bean
  public PlanRuntime planRuntime(SkillRuntime skills, WorkspaceService workspace) {
    return new PlanRuntime(skills, workspace);
  }
}
