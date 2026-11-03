package dev.myfrei.repopilot.progress;
import java.util.UUID;
import dev.myfrei.repopilot.agent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/agent")
@ConditionalOnProperty(name="repopilot.agent.enabled",havingValue="true")
public class GuardedAgentController {
 private final AgentEngine engine;
 public GuardedAgentController(AgentEngine baseline,CycleStopRule rule){this.engine=baseline.withStopRule(rule);}
 @PostMapping("/conversations/{id}/guarded-runs")public AgentRun run(@PathVariable UUID id,@RequestBody AgentRunRequest request){return engine.run(id,request.question());}
}
