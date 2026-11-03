package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.ui.SessionSnapshot;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/capstone/sessions")
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
      "repopilot.sessions.enabled",
      "repopilot.capstone.enabled"
    },
    havingValue = "true")
public class CapstoneController {
  private final CapstoneService service;

  public CapstoneController(CapstoneService service) {
    this.service = service;
  }

  @PostMapping
  public CapstoneSession create(@RequestBody String raw) {
    var fields = closed(raw, Set.of("taskId"));
    return service.create(text(fields, "taskId"));
  }

  @GetMapping("/{id}")
  public CapstoneSession session(@PathVariable UUID id) {
    return service.session(id);
  }

  @PostMapping("/{id}/prepare")
  public SessionSnapshot prepare(@PathVariable UUID id, @RequestBody String raw) {
    var fields = closed(raw, Set.of("requestId", "baselineReportId"));
    return service.prepare(id, uuid(fields, "requestId"), uuid(fields, "baselineReportId"));
  }

  @PostMapping("/{id}/review")
  public SessionSnapshot review(@PathVariable UUID id, @RequestBody String raw) {
    var fields = closed(raw, Set.of("requestId", "patchId", "passingReportId"));
    return service.review(
        id, uuid(fields, "requestId"), uuid(fields, "patchId"), uuid(fields, "passingReportId"));
  }

  @GetMapping("/{id}/report")
  public CapstoneReport report(@PathVariable UUID id) {
    return service.report(id);
  }

  private static Map<String, Object> closed(String raw, Set<String> fields) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 1024) throw new IllegalArgumentException();
      return AgentJson.arguments(raw, fields, fields);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Closed capstone JSON required");
    }
  }

  private static String text(Map<String, Object> fields, String key) {
    if (!(fields.get(key) instanceof String value))
      throw new IllegalArgumentException("String field required");
    return value;
  }

  private static UUID uuid(Map<String, Object> fields, String key) {
    return UUID.fromString(text(fields, key));
  }
}
