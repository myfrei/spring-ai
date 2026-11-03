package dev.myfrei.repopilot.triage;
import java.util.UUID;
import dev.myfrei.repopilot.diagnostics.DiagnosticService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name={"repopilot.diagnostics.enabled","repopilot.evaluation.enabled"},havingValue="true")
public class TriageController {
 private final DiagnosticService diagnostics;
 public TriageController(DiagnosticService diagnostics){this.diagnostics=diagnostics;}
 @GetMapping("/api/diagnostics/runs/{id}/triage")
 public TriageQueue.Queue queue(@PathVariable UUID id,@RequestParam(defaultValue="10") int limit){return TriageQueue.build(this.diagnostics.analyze(id),limit);}
}
