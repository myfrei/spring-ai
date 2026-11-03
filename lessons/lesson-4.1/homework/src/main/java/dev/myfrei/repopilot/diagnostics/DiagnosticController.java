package dev.myfrei.repopilot.diagnostics;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name = {"repopilot.diagnostics.enabled", "repopilot.evaluation.enabled"}, havingValue = "true")
@RequestMapping("/api/diagnostics")
public class DiagnosticController {
    private final DiagnosticService service;
    private final DiagnosticLab lab;
    private final TraceStore traces;
    public DiagnosticController(DiagnosticService service, DiagnosticLab lab, TraceStore traces) { this.service = service; this.lab = lab; this.traces = traces; }
    @PostMapping("/lab") public DiagnosticReport lab() { return this.lab.replay(); }
    @GetMapping("/runs/latest") public DiagnosticReport latest() { return this.service.latest(); }
    @GetMapping("/runs/{id}") public DiagnosticReport analyze(@PathVariable UUID id) { return this.service.analyze(id); }
    @GetMapping("/traces/{id}") public DiagnosticTrace trace(@PathVariable UUID id) { return this.traces.load(id); }
    @GetMapping(value = "/runs/{id}/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=diagnosis-" + id + ".md")
                .body(DiagnosticMarkdown.render(this.service.analyze(id)));
    }
}
