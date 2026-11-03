package dev.myfrei.repopilot.compaction;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/context")
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
public class CompactionController {
  private final ContextRuntime runtime;

  public CompactionController(ContextRuntime runtime) {
    this.runtime = runtime;
  }

  @PostMapping("/conversations/{id}/runs")
  public ContextRuntime.ContextRunResult run(@PathVariable UUID id, @RequestBody String raw) {
    return runtime.run(id, ContextRequest.decode(raw));
  }

  @GetMapping("/runs/{id}/report")
  public CompactionReport report(@PathVariable UUID id) {
    return runtime.report(id);
  }
}
