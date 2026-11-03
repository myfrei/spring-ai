package dev.myfrei.repopilot.stability;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/verification/conversations")
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
public class StabilityController {
  private final VerificationStabilityChecker checker;

  public StabilityController(VerificationStabilityChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/{id}/stability/check")
  public VerificationStabilityReport check(@PathVariable UUID id, @RequestBody String raw) {
    return checker.check(id, StabilityRequest.decode(raw));
  }
}
