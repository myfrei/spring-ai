package dev.myfrei.repopilot.homework;

import java.util.UUID;
import dev.myfrei.repopilot.evaluation.RunStore;
import dev.myfrei.repopilot.evaluation.Experiment.Mode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="repopilot.evaluation.enabled",havingValue="true")
public class PairedComparisonController {
    private final RunStore runs;private final PairedRunComparator comparison;
    public PairedComparisonController(RunStore runs,PairedRunComparator comparison) { this.runs=runs;this.comparison=comparison; }
    @GetMapping("/api/evaluation/pairs") public PairedRunComparator.Report compare(@RequestParam UUID baseline,@RequestParam UUID candidate,@RequestParam(defaultValue="RAG") Mode mode) { return this.comparison.compare(this.runs.load(baseline),this.runs.load(candidate),mode); }
}
