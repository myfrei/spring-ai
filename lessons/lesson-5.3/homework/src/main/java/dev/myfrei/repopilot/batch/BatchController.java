package dev.myfrei.repopilot.batch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/controlled/proposals")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled"
    },
    havingValue = "true")
public class BatchController {
  private final BatchChecker checker;

  public BatchController(BatchChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/batch-check")
  public BatchReport check(@RequestBody String raw) {
    return checker.check(BatchRequest.decode(raw).proposalIds());
  }
}
