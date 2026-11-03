package dev.myfrei.repopilot.verification;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.compaction.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
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
public class VerificationController {
  private final VerificationService service;
  private final VerificationRuntime runtime;

  public VerificationController(VerificationService service, VerificationRuntime runtime) {
    this.service = service;
    this.runtime = runtime;
  }

  @PostMapping("/api/verification/conversations/{id}/requests")
  public VerificationRequest request(@PathVariable UUID id, @RequestBody String raw) {
    return service.request(id, value(raw, "command"));
  }

  @GetMapping("/api/verification/requests/{id}")
  public VerificationRequest request(@PathVariable UUID id) {
    return service.request(id);
  }

  @PostMapping("/api/verification/requests/{id}/decision")
  public VerificationRequest decision(@PathVariable UUID id, @RequestBody String raw) {
    return service.decide(id, value(raw, "decision"));
  }

  @GetMapping("/api/verification/reports/{id}")
  public VerificationReport report(@PathVariable UUID id) {
    return service.report(id);
  }

  @GetMapping("/api/verification/conversations/{id}/reports")
  public List<VerificationReport> reports(@PathVariable UUID id) {
    return service.reports(id);
  }

  @PostMapping("/api/verified/conversations/{id}/runs")
  public VerificationRuntime.VerifiedRunResult run(@PathVariable UUID id, @RequestBody String raw) {
    return runtime.run(id, ContextRequest.decode(raw));
  }

  private static String value(String raw, String name) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 256) throw new IllegalArgumentException();
      var a = AgentJson.arguments(raw, Set.of(name), Set.of(name));
      if (!(a.get(name) instanceof String value)) throw new IllegalArgumentException();
      return value;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Closed request required");
    }
  }
}
