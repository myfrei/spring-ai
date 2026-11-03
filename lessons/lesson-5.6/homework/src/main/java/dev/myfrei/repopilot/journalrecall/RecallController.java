package dev.myfrei.repopilot.journalrecall;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/context/runs")
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
public class RecallController {
  private final RecallChecker checker;

  public RecallController(RecallChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/{id}/recall")
  public RecallReport recall(@PathVariable UUID id, @RequestBody String raw) {
    return checker.check(id, RecallRequest.decode(raw));
  }
}
