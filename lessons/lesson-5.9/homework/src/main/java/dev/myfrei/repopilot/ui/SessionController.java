package dev.myfrei.repopilot.ui;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
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
public class SessionController {
  private final SessionCoordinator coordinator;

  public SessionController(SessionCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @PostMapping("/conversations/{id}/jobs")
  public SessionSnapshot submit(@PathVariable UUID id, @RequestBody String raw) {
    var request = SessionRequest.decode(raw);
    return coordinator.submit(id, request.requestId(), request.task());
  }

  @GetMapping("/conversations/{id}/jobs")
  public List<SessionSnapshot> list(@PathVariable UUID id) {
    return coordinator.sessions(id);
  }

  @GetMapping("/jobs/{id}")
  public SessionSnapshot snapshot(@PathVariable UUID id) {
    return coordinator.snapshot(id);
  }

  @GetMapping("/jobs/{id}/events")
  public SessionCoordinator.EventPage events(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "0") int waitMs) {
    return coordinator.events(id, after, waitMs);
  }

  @PostMapping("/jobs/{id}/commands")
  public SessionSnapshot command(@PathVariable UUID id, @RequestBody String raw) {
    return coordinator.command(id, SessionCommand.decode(raw));
  }

  @GetMapping("/jobs/{id}/approvals/{kind}/{approvalId}")
  public Object approval(
      @PathVariable UUID id, @PathVariable String kind, @PathVariable UUID approvalId) {
    return coordinator.approval(id, kind, approvalId);
  }

  @GetMapping("/skills")
  public Object skills() {
    return coordinator.skillCatalog();
  }
}
