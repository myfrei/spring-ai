package dev.myfrei.repopilot.evaluation;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
class RunStoreTest {
    @TempDir Path directory;
    @Test void atomicStoreSurvivesNewInstanceWithRawResultsAndJudgments() throws Exception {
        var run = EvaluationFixtures.run(List.of(EvaluationFixtures.row(0, Mode.CHAT, Outcome.COMPLETED, 15)), List.of(), Map.of(0, new Judgment(2, 2, null, "read sources", "date")));
        var store = new RunStore(directory.toString()); store.save(run);
        assertThat(new RunStore(directory.toString()).load(run.id())).isEqualTo(run);
        try (var paths = Files.list(directory)) { assertThat(paths.map(path -> path.getFileName().toString())).containsExactly("run-" + run.id() + ".json"); }
    }
    @Test void interruptedProcessKeepsPartialRowsWithoutInventingCompletion() {
        var completed = EvaluationFixtures.run(List.of(EvaluationFixtures.row(0, Mode.CHAT, Outcome.COMPLETED, 15)), List.of(), Map.of());
        var pending = new Experiment(completed.id(), completed.startedAt(), "", State.RUNNING, completed.request(), null, 6, List.of(), completed.results(), Map.of(), "");
        var store = new RunStore(directory.toString()); store.save(pending); store.recoverInterrupted(); var recovered = store.load(pending.id());
        assertThat(recovered.state()).isEqualTo(State.INTERRUPTED); assertThat(recovered.results()).hasSize(1); assertThat(recovered.planned()).isEqualTo(6);
    }
    @Test void missingAndCorruptReportsAreExplicitPublicErrors() throws Exception {
        var store = new RunStore(directory.toString()); UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> store.load(id)).isInstanceOf(EvaluationProblem.class).extracting("code").isEqualTo("NOT_FOUND");
        Files.writeString(directory.resolve("run-" + id + ".json"), "broken");
        assertThatThrownBy(() -> store.load(id)).isInstanceOf(EvaluationProblem.class).extracting("code").isEqualTo("REPORT_CORRUPT");
    }
}
