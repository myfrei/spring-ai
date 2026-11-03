package dev.myfrei.repopilot.frontier;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plans/runs")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled",
      "repopilot.plans.enabled"
    },
    havingValue = "true")
public class FrontierController {
  private final FrontierChecker checker;

  public FrontierController(FrontierChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/{id}/frontier/check")
  public FrontierReport check(@PathVariable UUID id, @RequestBody String raw) {
    return checker.check(id, FrontierRequest.decode(raw));
  }
}
