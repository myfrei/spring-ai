package dev.myfrei.repopilot.skills;

import dev.myfrei.repopilot.controlled.ControlledService;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled"
    },
    havingValue = "true")
public class SkillConfiguration {
  @Bean
  public SkillCatalog skillCatalog(
      @Value("${repopilot.skills.root:./workspace-fixture/.agents/skills}") String root) {
    return new SkillCatalog(Path.of(root));
  }

  @Bean
  public SkillRuntime skillRuntime(ControlledService controlled, SkillCatalog catalog) {
    return new SkillRuntime(controlled, catalog);
  }
}
