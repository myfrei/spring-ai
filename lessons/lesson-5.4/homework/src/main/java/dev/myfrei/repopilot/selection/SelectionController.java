package dev.myfrei.repopilot.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills/selection")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled"
    },
    havingValue = "true")
public class SelectionController {
  private final SelectionChecker checker;

  public SelectionController(SelectionChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/check")
  public SelectionReport check(@RequestBody String raw) {
    return checker.check(SelectionRequest.decode(raw));
  }
}
