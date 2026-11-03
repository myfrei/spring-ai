package dev.myfrei.repopilot.diagnostics;

import java.util.*;
import org.springframework.core.io.DefaultResourceLoader;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
final class DiagnosticFixtures {
    static CorpusCatalog corpus() { return new CorpusCatalog(new DefaultResourceLoader()); }
    static EvaluationDataset dataset() { return new EvaluationDataset(new DefaultResourceLoader(), corpus()); }
    static EvaluationCase entry() { return dataset().cases(Split.DEV).getFirst(); }
    static Result row(Mode mode, Outcome outcome, Boolean retrieved, Boolean provided) {
        return new Result(0, "dev-01", 1, mode, entry().question(), outcome, "Fixture answer", outcome == Outcome.FAILED ? "OUTPUT_LIMIT" : "",
                new Timing(20, 10, 5, 10.0, 15.0, 5.0), 200, 100, 5, 2, List.of(), retrieved, provided, "a".repeat(64));
    }
    static Judgment judgment(int answer, int citations) { return new Judgment(answer, citations, null, "manual", "date"); }
    static Experiment run(Result row, boolean compatible) {
        var conditions = new Conditions(compatible ? dataset().version() : "d".repeat(64), corpus().snapshot().version(),
                new ChatProfile("local", "a".repeat(64), .2, 4096, 256, "b".repeat(64)), null, "fixture", 3584, "lesson-2.4-v1", "NOT_MEASURED", "fixture");
        return new Experiment(UUID.randomUUID(), "2026-10-25T20:00:00Z", "", State.COMPLETED,
                new Request(Split.DEV, List.of("dev-01"), List.of(row.mode()), 1, 4, .35, false), conditions, 1, List.of(), List.of(row), Map.of(), "");
    }
}
