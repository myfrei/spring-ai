package dev.myfrei.repopilot.controlled;

import dev.myfrei.repopilot.agent.AgentEngine;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled"
    },
    havingValue = "true")
public class ControlledConfiguration {
  @Bean
  public ControlledService controlledService(AgentEngine engine, WorkspaceService workspace) {
    return new ControlledService(engine, workspace, Clock.systemUTC());
  }
}
