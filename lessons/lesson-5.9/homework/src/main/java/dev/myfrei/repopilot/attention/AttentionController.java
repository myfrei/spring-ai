package dev.myfrei.repopilot.attention;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions/conversations")
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
public class AttentionController {
  private final AttentionQueueChecker checker;

  public AttentionController(AttentionQueueChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/{id}/attention/check")
  public AttentionQueueReport check(@PathVariable UUID id, @RequestBody String raw) {
    return checker.check(id, AttentionRequest.decode(raw));
  }
}
