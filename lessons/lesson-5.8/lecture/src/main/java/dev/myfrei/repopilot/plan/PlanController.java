package dev.myfrei.repopilot.plan;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plans")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled",
      "repopilot.plans.enabled"
    },
    havingValue = "true")
public class PlanController {
  private final PlanRuntime runtime;

  public PlanController(PlanRuntime runtime) {
    this.runtime = runtime;
  }

  @PostMapping("/conversations/{id}/runs")
  public PlanRuntime.PlanRunResult run(@PathVariable UUID id, @RequestBody String raw) {
    var request = PlanRequest.decode(raw);
    return runtime.run(id, request.question(), request.mode(), request.skills(), request.plan());
  }

  @GetMapping("/runs/{id}")
  public PlanSnapshot snapshot(@PathVariable UUID id) {
    return runtime.snapshot(id);
  }
}
