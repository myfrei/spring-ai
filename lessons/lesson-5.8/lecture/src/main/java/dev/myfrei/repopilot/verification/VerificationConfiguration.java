package dev.myfrei.repopilot.verification;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.compaction.ContextRuntime;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
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
public class VerificationConfiguration {
  @Bean
  public VerificationService verificationService(
      WorkspaceService workspace,
      AgentStore store,
      @Value("${repopilot.verification.image-id}") String image) {
    return new VerificationService(
        workspace, store, new DockerVerificationRunner(), image, Clock.systemUTC());
  }

  @Bean
  public VerificationRuntime verificationRuntime(
      ContextRuntime contexts, VerificationService service) {
    return new VerificationRuntime(contexts, service);
  }
}
