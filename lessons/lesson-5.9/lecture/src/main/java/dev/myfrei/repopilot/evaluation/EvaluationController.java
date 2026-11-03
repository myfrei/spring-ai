package dev.myfrei.repopilot.evaluation;

import java.util.Map;
import java.util.UUID;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "repopilot.evaluation.enabled", havingValue = "true")
@RequestMapping("/api/evaluation")
public class EvaluationController {
    private final EvaluationDataset dataset;
    private final ExperimentCoordinator coordinator;
    private final RunStore store;
    public EvaluationController(EvaluationDataset dataset, ExperimentCoordinator coordinator, RunStore store) {
        this.dataset = dataset; this.coordinator = coordinator; this.store = store;
    }
    @GetMapping("/dataset") public Object dataset(@RequestParam(defaultValue = "DEV") Split split) {
        return Map.of("version", this.dataset.version(), "split", split, "cases", this.dataset.cases(split));
    }
    @GetMapping("/runs") public Object list() {
        return this.store.list().stream().limit(20).map(run -> Map.of("id", run.id(), "state", run.state(), "startedAt", run.startedAt(), "processed", run.results().size(), "planned", run.planned())).toList();
    }
    @PostMapping("/runs") public ResponseEntity<Experiment> start(@RequestBody Request request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(this.coordinator.start(request));
    }
    @GetMapping("/runs/{id}") public Object get(@PathVariable UUID id) {
        var run = this.store.load(id); return Map.of("run", run, "summary", ComparisonSummary.summarize(run));
    }
    @PostMapping("/runs/{id}/cancel") public Object cancel(@PathVariable UUID id) { this.coordinator.cancel(id); return Map.of("requested", true); }
    @PutMapping("/runs/{id}/judgments/{ordinal}") public Object judge(@PathVariable UUID id, @PathVariable int ordinal, @RequestBody Judgment judgment) {
        return this.coordinator.judge(id, ordinal, judgment);
    }
    @GetMapping(value = "/runs/{id}/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=run-" + id + ".md")
                .cacheControl(CacheControl.noStore()).body(MarkdownReport.render(this.store.load(id)));
    }
}
