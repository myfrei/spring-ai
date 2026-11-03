package dev.myfrei.repopilot.selection;

import dev.myfrei.repopilot.skills.SkillCatalog;
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
public class SelectionConfiguration {
  @Bean
  public SkillSelectionPolicy skillSelectionPolicy() {
    return new SkillSelectionPolicy();
  }

  @Bean
  public SelectionChecker selectionChecker(SkillCatalog catalog, SkillSelectionPolicy policy) {
    return new SelectionChecker(catalog, policy);
  }
}
