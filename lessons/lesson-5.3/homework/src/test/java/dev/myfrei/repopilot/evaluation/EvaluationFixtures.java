package dev.myfrei.repopilot.evaluation;

import java.util.*;
import dev.myfrei.repopilot.context.CorpusCatalog;
import org.springframework.core.io.DefaultResourceLoader;
import dev.myfrei.repopilot.evaluation.Experiment.*;
final class EvaluationFixtures {
    static EvaluationDataset dataset() { return new EvaluationDataset(new DefaultResourceLoader(), new CorpusCatalog(new DefaultResourceLoader())); }
    static Request request() { return new Request(Split.DEV, List.of("dev-01", "dev-02"), null, 1, null, null, false); }
    static Result row(int id, Mode mode, Outcome outcome, double time) {
        return new Result(id, "dev-01", 1, mode, "Q", outcome, "Answer", outcome == Outcome.FAILED ? "MODEL_ERROR" : "",
                new Timing(time, 10, 5, outcome == Outcome.NO_EVIDENCE ? null : 30.0, outcome == Outcome.NO_EVIDENCE ? null : 20.0,
                    outcome == Outcome.NO_EVIDENCE ? null : 10.0), 100, 80, 4, 1, List.of(), null, mode != Mode.CHAT, "hash");
    }
    static Experiment run(List<Result> rows, List<Result> warmups, Map<Integer, Judgment> judgments) {
        return new Experiment(UUID.randomUUID(), "2026-10-25T20:00:00Z", "", State.COMPLETED, request(), null, 6, warmups, rows, judgments, "");
    }
}
