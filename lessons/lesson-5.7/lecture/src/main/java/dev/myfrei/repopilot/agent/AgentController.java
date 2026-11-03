package dev.myfrei.repopilot.agent;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
@ConditionalOnProperty(name = "repopilot.agent.enabled", havingValue = "true")
public class AgentController {
  private final AgentEngine engine;
  private final AgentStore store;

  public AgentController(AgentEngine engine, AgentStore store) {
    this.engine = engine;
    this.store = store;
  }

  @PostMapping("/conversations")
  public AgentConversation create() {
    return store.createConversation();
  }

  @GetMapping("/conversations/{id}")
  public AgentConversation conversation(@PathVariable UUID id) {
    return store.conversation(id);
  }

  @PostMapping("/conversations/{id}/runs")
  public AgentRun run(@PathVariable UUID id, @RequestBody AgentRunRequest request) {
    return engine.run(id, request.question());
  }

  @GetMapping("/runs/{id}")
  public AgentRun getRun(@PathVariable UUID id) {
    return store.run(id);
  }

  @GetMapping("/runs/{id}/events")
  public List<AgentEvent> events(@PathVariable UUID id) {
    return store.events(id);
  }

  @GetMapping("/runs/{id}/messages")
  public List<AgentMessage> messages(@PathVariable UUID id) {
    return store.messages(id);
  }
}
