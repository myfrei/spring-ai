package dev.myfrei.repopilot.evidence;
import java.util.UUID;
import dev.myfrei.repopilot.agent.AgentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/agent/runs")
@ConditionalOnProperty(name={"repopilot.agent.enabled","repopilot.workspace.enabled"},havingValue="true")
public class EvidenceController {
 private final AgentStore store;private final EvidenceChecker checker;
 public EvidenceController(AgentStore store,EvidenceChecker checker){this.store=store;this.checker=checker;}
 @PostMapping("/{id}/evidence/check")public EvidenceReport check(@PathVariable UUID id,@RequestBody String raw){
  var request=EvidenceClaimsRequest.decode(raw);var run=store.run(id);
  if(run.status().equals("RUNNING"))throw new EvidenceProblem("EVIDENCE_RUN_ACTIVE");
  return checker.check(id,request.claims());
 }
}
