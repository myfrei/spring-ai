package dev.myfrei.repopilot.evaluation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;
class ExperimentCoordinatorTest {
    @TempDir Path directory;
    @Test void savesProgressKeepsWarmupsSeparateAndDoesNotCreateConversations() {
        var probe = mock(ExperimentProbe.class); var engine = mock(ComparisonEngine.class); var store = new RunStore(directory.toString());
        when(engine.execute(anyInt(), any(), anyInt(), any(), any())).thenAnswer(call -> EvaluationFixtures.row(call.getArgument(0), call.getArgument(3), Outcome.COMPLETED, 25));
        try (var coordinator = new ExperimentCoordinator(EvaluationFixtures.dataset(), probe, engine, store)) {
            var request = new Request(Split.HOLDOUT, List.of("holdout-01"), null, 1, null, null, true);
            var started = coordinator.start(request); var result = terminal(store, started.id());
            assertThat(result.state()).isEqualTo(State.COMPLETED); assertThat(result.planned()).isEqualTo(3); assertThat(result.results()).hasSize(3); assertThat(result.warmups()).hasSize(3);
            verify(engine, times(3)).execute(anyInt(), argThat(entry -> entry.id().equals("dev-01")), eq(0), any(), eq(request));
            verify(engine, times(3)).execute(anyInt(), argThat(entry -> entry.id().equals("holdout-01")), eq(1), any(), eq(request));
        }
    }
    @Test void allowsOneRunAndCancellationDoesNotLaunchRemainingTasks() throws Exception {
        var probe = mock(ExperimentProbe.class); var engine = mock(ComparisonEngine.class); var store = new RunStore(directory.toString()); var entered = new CountDownLatch(1);
        when(engine.execute(anyInt(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            entered.countDown(); try { new CountDownLatch(1).await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return EvaluationFixtures.row(call.getArgument(0), call.getArgument(3), Outcome.CANCELLED, 10);
        });
        try (var coordinator = new ExperimentCoordinator(EvaluationFixtures.dataset(), probe, engine, store)) {
            var run = coordinator.start(EvaluationFixtures.request()); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> coordinator.start(EvaluationFixtures.request())).isInstanceOf(EvaluationProblem.class).extracting("code").isEqualTo("EXPERIMENT_BUSY");
            coordinator.cancel(run.id()); var result = terminal(store, run.id()); assertThat(result.state()).isEqualTo(State.CANCELLED);
            assertThat(result.results()).hasSize(1); verify(engine, times(1)).execute(anyInt(), any(), anyInt(), any(), any());
        }
    }
    @Test void changedConditionsKeepRawAnswerButInvalidateRowAndStopRun() {
        var probe = mock(ExperimentProbe.class); var engine = mock(ComparisonEngine.class); var store = new RunStore(directory.toString()); var checks = new AtomicInteger();
        doAnswer(call -> { if (checks.incrementAndGet() == 2) throw new EvaluationProblem("EXPERIMENT_CHANGED", "changed"); return null; }).when(probe).requireUnchanged(any(), any());
        when(engine.execute(anyInt(), any(), anyInt(), any(), any())).thenReturn(EvaluationFixtures.row(0, Mode.CHAT, Outcome.COMPLETED, 10));
        try (var coordinator = new ExperimentCoordinator(EvaluationFixtures.dataset(), probe, engine, store)) {
            var result = terminal(store, coordinator.start(EvaluationFixtures.request()).id());
            assertThat(result.state()).isEqualTo(State.FAILED); assertThat(result.results()).hasSize(1);
            assertThat(result.results().getFirst().answer()).isEqualTo("Answer"); assertThat(result.results().getFirst().code()).isEqualTo("EXPERIMENT_CHANGED");
            assertThat(result.results().getFirst().outcome()).isEqualTo(Outcome.FAILED);
        }
    }
    @Test void warmupFailureStopsMeasurementsAndIsNotAZeroLatencyWin() {
        var probe = mock(ExperimentProbe.class); var engine = mock(ComparisonEngine.class); var store = new RunStore(directory.toString());
        when(engine.execute(anyInt(), any(), anyInt(), any(), any())).thenReturn(EvaluationFixtures.row(-1, Mode.CHAT, Outcome.FAILED, 1));
        try (var coordinator = new ExperimentCoordinator(EvaluationFixtures.dataset(), probe, engine, store)) {
            var result = terminal(store, coordinator.start(new Request(Split.DEV, List.of("dev-01"), null, 1, null, null, true)).id());
            assertThat(result.state()).isEqualTo(State.FAILED); assertThat(result.results()).isEmpty(); assertThat(result.warmups()).hasSize(1);
        }
    }
    @Test void judgmentsRequireExistingEligibleRowsAndUseServerTimestamp() {
        var probe = mock(ExperimentProbe.class); var engine = mock(ComparisonEngine.class); var store = new RunStore(directory.toString());
        when(engine.execute(anyInt(), any(), anyInt(), any(), any())).thenAnswer(call -> EvaluationFixtures.row(call.getArgument(0), call.getArgument(3), Outcome.COMPLETED, 25));
        try (var coordinator = new ExperimentCoordinator(EvaluationFixtures.dataset(), probe, engine, store)) {
            var run = terminal(store, coordinator.start(EvaluationFixtures.request()).id());
            var judged = coordinator.judge(run.id(), 0, new Judgment(2, 1, null, "Checked", "forged"));
            assertThat(judged.judgments().get(0).reviewedAt()).isNotEqualTo("forged");
            assertThatThrownBy(() -> coordinator.judge(run.id(), 999, new Judgment(2, 2, null, "", ""))).isInstanceOf(EvaluationProblem.class);
        }
    }
    @Test void postRequestProbeFailureIsUnverifiedRatherThanClaimedModelChange() {
        var probe = mock(ExperimentProbe.class); var engine = mock(ComparisonEngine.class); var store = new RunStore(directory.toString()); var checks = new AtomicInteger();
        doAnswer(call -> { if (checks.incrementAndGet() == 2) throw new EvaluationProblem("MODEL_UNAVAILABLE", "offline"); return null; }).when(probe).requireUnchanged(any(), any());
        when(engine.execute(anyInt(), any(), anyInt(), any(), any())).thenReturn(EvaluationFixtures.row(0, Mode.CHAT, Outcome.COMPLETED, 10));
        try (var coordinator = new ExperimentCoordinator(EvaluationFixtures.dataset(), probe, engine, store)) {
            var run = terminal(store, coordinator.start(EvaluationFixtures.request()).id());
            assertThat(run.state()).isEqualTo(State.FAILED); assertThat(run.results()).hasSize(1);
            assertThat(run.results().getFirst().code()).isEqualTo("CONDITIONS_UNVERIFIED");
        }
    }
    private static Experiment terminal(RunStore store, UUID id) {
        await().atMost(Duration.ofSeconds(4)).until(() -> store.load(id).state() != State.RUNNING); return store.load(id);
    }
}
