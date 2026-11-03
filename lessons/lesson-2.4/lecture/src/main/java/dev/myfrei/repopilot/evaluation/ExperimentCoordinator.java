package dev.myfrei.repopilot.evaluation;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import dev.myfrei.repopilot.evaluation.Experiment.*;

public class ExperimentCoordinator implements AutoCloseable {
    private final EvaluationDataset dataset;
    private final ExperimentProbe probe;
    private final ComparisonEngine engine;
    private final RunStore store;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private Active active;
    public ExperimentCoordinator(EvaluationDataset dataset, ExperimentProbe probe, ComparisonEngine engine, RunStore store) {
        this.dataset = dataset; this.probe = probe; this.engine = engine; this.store = store; this.store.recoverInterrupted();
    }
    public synchronized Experiment start(Request request) {
        if (this.active != null) throw new EvaluationProblem("EXPERIMENT_BUSY", "Другой эксперимент ещё выполняется. Дождитесь завершения или остановите его.");
        var cases = this.dataset.select(request);
        var initial = new Experiment(UUID.randomUUID(), Instant.now().toString(), "", State.RUNNING, request, null,
                cases.size() * request.repeats() * request.modes().size(), List.of(), List.of(), Map.of(), "");
        this.store.save(initial); var work = new Active(initial); this.active = work;
        this.workers.submit(() -> execute(work, cases)); return initial;
    }
    public synchronized void cancel(UUID id) {
        if (this.active == null || !this.active.run.id().equals(id)) throw new EvaluationProblem("NOT_RUNNING", "Этот эксперимент уже не выполняется.");
        this.active.cancelled.set(true); if (this.active.thread != null) this.active.thread.interrupt();
    }
    public synchronized Experiment judge(UUID id, int ordinal, Judgment judgment) {
        var run = this.store.load(id);
        if (run.state() == State.RUNNING) throw new EvaluationProblem("EXPERIMENT_BUSY", "Оцените ответы после остановки/завершения эксперимента.");
        var row = run.results().stream().filter(result -> result.ordinal() == ordinal).findFirst()
                .orElseThrow(() -> new EvaluationProblem("NOT_FOUND", "Строка результата не найдена."));
        if (row.outcome() != Outcome.COMPLETED && row.outcome() != Outcome.NO_EVIDENCE) throw new IllegalArgumentException("Cannot judge failed request");
        var judgments = new LinkedHashMap<>(run.judgments());
        judgments.put(ordinal, new Judgment(judgment.answer(), judgment.citations(), judgment.refusalCorrect(), judgment.notes(), Instant.now().toString()));
        var updated = new Experiment(run.id(), run.startedAt(), run.finishedAt(), run.state(), run.request(), run.conditions(), run.planned(), run.warmups(), run.results(), judgments, run.message());
        this.store.save(updated); return updated;
    }
    private void execute(Active work, List<EvaluationCase> cases) {
        work.thread = Thread.currentThread();
        try {
            checkCancellation(work);
            var conditions = this.probe.capture(work.run.request().modes());
            work.run = new Experiment(work.run.id(), work.run.startedAt(), "", State.RUNNING, work.run.request(), conditions,
                    work.run.planned(), List.of(), List.of(), Map.of(), ""); this.store.save(work.run);
            if (work.run.request().warmup()) {
                var warm = this.dataset.cases(Split.DEV).getFirst(); int ordinal = -1;
                for (Mode mode : work.run.request().modes()) {
                    checkCancellation(work); var row = one(work, ordinal--, warm, 0, mode); append(work, row, true);
                    if (row.outcome() == Outcome.FAILED) throw new EvaluationProblem("WARMUP_FAILED", "Warm-up завершился ошибкой " + row.code() + ". Измерения не начаты.");
                }
            }
            for (var task : RunSchedule.build(cases, work.run.request())) {
                checkCancellation(work);
                var row = one(work, task.ordinal(), task.entry(), task.repetition(), task.mode()); append(work, row, false);
                if (Set.of("EXPERIMENT_CHANGED", "MODEL_CHANGED", "INDEX_CHANGED", "STALE_INDEX", "INDEX_PROFILE_MISMATCH", "CONDITIONS_UNVERIFIED").contains(row.code())) {
                    throw new EvaluationProblem(row.code(), "Условия запроса изменились или не подтверждены. Прогон остановлен, исходные результаты сохранены.");
                }
            }
            checkCancellation(work); finish(work, State.COMPLETED, "Все запланированные запросы обработаны; наличие ошибок смотрите в строках.");
        } catch (RuntimeException error) {
            boolean cancelled = work.cancelled.get() || Thread.currentThread().isInterrupted();
            Thread.interrupted();
            finish(work, cancelled ? State.CANCELLED : State.FAILED, cancelled ? "Остановлено пользователем; оставшиеся запросы не запускались."
                    : error instanceof ConversationProblem problem ? problem.code() + ": " + problem.getMessage() : "EXPERIMENT_ERROR: эксперимент остановлен.");
        } finally { synchronized (this) { if (this.active == work) this.active = null; } }
    }
    private Result one(Active work, int ordinal, EvaluationCase entry, int repetition, Mode mode) {
        this.probe.requireUnchanged(work.run.conditions(), work.run.request().modes());
        var row = this.engine.execute(ordinal, entry, repetition, mode, work.run.request());
        if (work.cancelled.get()) return row;
        try { this.probe.requireUnchanged(work.run.conditions(), work.run.request().modes()); return row; }
        catch (RuntimeException error) {
            return new Result(row.ordinal(), row.caseId(), row.repetition(), row.mode(), row.question(), Outcome.FAILED, row.answer(),
                    error instanceof EvaluationProblem problem && problem.code().equals("EXPERIMENT_CHANGED") ? "EXPERIMENT_CHANGED" : "CONDITIONS_UNVERIFIED", row.timing(), row.estimatedInputTokens(), row.providerInputTokens(), row.providerOutputTokens(),
                    row.retrievedChunks(), row.sources(), row.retrievedEvidenceHit(), row.providedEvidenceHit(), row.promptHash());
        }
    }
    private void append(Active work, Result row, boolean warmup) {
        var warmups = new ArrayList<>(work.run.warmups()); var results = new ArrayList<>(work.run.results());
        if (warmup) warmups.add(row); else results.add(row);
        work.run = new Experiment(work.run.id(), work.run.startedAt(), "", State.RUNNING, work.run.request(), work.run.conditions(),
                work.run.planned(), warmups, results, Map.of(), ""); this.store.save(work.run);
    }
    private void finish(Active work, State state, String message) {
        work.run = new Experiment(work.run.id(), work.run.startedAt(), Instant.now().toString(), state, work.run.request(), work.run.conditions(),
                work.run.planned(), work.run.warmups(), work.run.results(), work.run.judgments(), message); this.store.save(work.run);
    }
    private static void checkCancellation(Active work) { if (work.cancelled.get() || Thread.currentThread().isInterrupted()) throw new EvaluationProblem("CANCELLED", "Остановка."); }
    @Override public synchronized void close() { if (this.active != null) this.active.cancelled.set(true); this.workers.shutdownNow(); }
    private static class Active {
        volatile Experiment run; volatile Thread thread; final AtomicBoolean cancelled = new AtomicBoolean();
        Active(Experiment run) { this.run = run; }
    }
}
