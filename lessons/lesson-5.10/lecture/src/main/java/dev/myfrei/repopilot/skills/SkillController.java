package dev.myfrei.repopilot.skills;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.RunMode;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills")
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled"
    },
    havingValue = "true")
public class SkillController {
  private final SkillRuntime runtime;
  private final AgentStore store;

  public SkillController(SkillRuntime runtime, AgentStore store) {
    this.runtime = runtime;
    this.store = store;
  }

  @GetMapping("/catalog")
  public List<SkillMetadata> catalog() {
    return runtime.catalog().snapshot().metadata();
  }

  @PostMapping("/conversations/{id}/runs")
  public SkillRuntime.SkillRunResult run(@PathVariable UUID id, @RequestBody String body) {
    if (body == null || AgentJson.bytes(body) > 4096)
      throw new IllegalArgumentException("Bounded request");
    Map<String, Object> args;
    try {
      args =
          AgentJson.arguments(
              body, Set.of("question", "mode", "skills"), Set.of("question", "mode"));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Closed JSON request required");
    }
    if (!(args.get("question") instanceof String question)
        || !(args.get("mode") instanceof String mode))
      throw new IllegalArgumentException("Question and mode required");
    List<String> skills = new ArrayList<>();
    if (args.containsKey("skills")) {
      if (!(args.get("skills") instanceof List<?> list) || list.size() > 3)
        throw new IllegalArgumentException("Skills list required");
      for (Object item : list) {
        if (!(item instanceof String name))
          throw new IllegalArgumentException("Skill name required");
        skills.add(name);
      }
    }
    return runtime.run(id, question, RunMode.valueOf(mode), skills);
  }

  @GetMapping("/runs/{id}/activations")
  public List<SkillSession.Activation> activations(@PathVariable UUID id) {
    store.run(id);
    return runtime.activations(id);
  }
}
