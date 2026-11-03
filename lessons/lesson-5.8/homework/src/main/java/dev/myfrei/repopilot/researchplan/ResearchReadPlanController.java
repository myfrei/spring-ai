package dev.myfrei.repopilot.researchplan;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/research/runs")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled",
      "repopilot.plans.enabled",
      "repopilot.compaction.enabled",
      "repopilot.verification.enabled",
      "repopilot.research.enabled"
    },
    havingValue = "true")
public class ResearchReadPlanController {
  private final ResearchReadPlanChecker checker;

  public ResearchReadPlanController(ResearchReadPlanChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/{id}/read-plan")
  public ResearchCheckPlanReport check(@PathVariable UUID id, @RequestBody String raw) {
    return checker.check(id, ResearchReadPlanRequest.decode(raw));
  }
}
