package dev.myfrei.repopilot.controlled;

import dev.myfrei.repopilot.agent.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/controlled")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled"
    },
    havingValue = "true")
public final class ControlledController {
  private final ControlledService service;
  private final AgentStore store;

  public ControlledController(ControlledService service, AgentStore store) {
    this.service = service;
    this.store = store;
  }

  @PostMapping("/conversations/{id}/runs")
  public AgentRun run(@PathVariable UUID id, @RequestBody String raw) {
    var args = decode(raw, Set.of("question", "mode"));
    if (!(args.get("question") instanceof String question)
        || !(args.get("mode") instanceof String mode))
      throw new ControlledProblem("INVALID_REQUEST");
    RunMode parsed;
    try {
      parsed = RunMode.valueOf(mode);
    } catch (IllegalArgumentException e) {
      throw new ControlledProblem("INVALID_REQUEST");
    }
    return service.run(id, question, parsed);
  }

  @GetMapping("/runs/{id}/proposals")
  public List<PatchProposal> proposals(@PathVariable UUID id) {
    store.run(id);
    return service.proposals(id);
  }

  @GetMapping("/proposals/{id}")
  public PatchProposal proposal(@PathVariable UUID id) {
    return service.proposal(id);
  }

  @PostMapping("/proposals/{id}/decision")
  public PatchProposal decision(@PathVariable UUID id, @RequestBody String raw) {
    var args = decode(raw, Set.of("decision"));
    if (!(args.get("decision") instanceof String decision))
      throw new ControlledProblem("INVALID_REQUEST");
    return service.decide(id, decision);
  }

  private static Map<String, Object> decode(String raw, Set<String> keys) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 8192) throw new IllegalArgumentException();
      return AgentJson.arguments(raw, keys, keys);
    } catch (RuntimeException failure) {
      throw new ControlledProblem("INVALID_REQUEST");
    }
  }
}
