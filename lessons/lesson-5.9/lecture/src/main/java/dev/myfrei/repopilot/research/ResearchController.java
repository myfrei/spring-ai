package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.AgentMessage;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/research")
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
public class ResearchController {
  private final ResearchRuntime runtime;

  public ResearchController(ResearchRuntime runtime) {
    this.runtime = runtime;
  }

  @PostMapping("/conversations/{id}/runs")
  public ResearchRuntime.ResearchRunResult run(@PathVariable UUID id, @RequestBody String raw) {
    return runtime.run(id, ResearchRequest.decode(raw));
  }

  @GetMapping("/runs/{id}")
  public ResearchSnapshot snapshot(@PathVariable UUID id) {
    return runtime.snapshot(id);
  }

  @PostMapping("/runs/{id}/cancel")
  public ResearchSnapshot cancel(@PathVariable UUID id) {
    return runtime.cancel(id);
  }

  @GetMapping("/runs/{parentId}/children/{childId}/messages")
  public List<AgentMessage> childMessages(@PathVariable UUID parentId, @PathVariable UUID childId) {
    return runtime.childMessages(parentId, childId);
  }
}
