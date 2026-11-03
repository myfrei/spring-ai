package dev.myfrei.repopilot.evaluation;

import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.*;
public final class ComparisonSummary {
    private ComparisonSummary() { }
    public record Distribution(int n, Double median, Double p95) { }
    public record Rate(int n, int hits, Double fraction) { }
    public record Group(Mode mode, int attempted, int completed, int noEvidence, int failed, int cancelled, int judged,
            Double meanAnswerScore, Double meanCitationScore, Distribution totalMs, Distribution modelTtftMs,
            Distribution endToEndTtftMs, Distribution estimatedInputTokens, Rate providedEvidence) { }
    public static List<Group> summarize(Experiment run) {
        return run.request().modes().stream().map(mode -> {
            var rows = run.results().stream().filter(row -> row.mode() == mode).toList();
            var completed = rows.stream().filter(row -> row.outcome() == Outcome.COMPLETED).toList();
            var eligible = rows.stream().filter(row -> row.outcome() == Outcome.COMPLETED || row.outcome() == Outcome.NO_EVIDENCE).toList();
            var judgments = eligible.stream().filter(row -> run.judgments().containsKey(row.ordinal())).map(row -> run.judgments().get(row.ordinal())).toList();
            var coverage = eligible.stream().filter(row -> row.providedEvidenceHit() != null).toList();
            int hits = (int) coverage.stream().filter(row -> row.providedEvidenceHit()).count();
            return new Group(mode, rows.size(), completed.size(), count(rows, Outcome.NO_EVIDENCE), count(rows, Outcome.FAILED), count(rows, Outcome.CANCELLED), judgments.size(),
                    judgments.isEmpty() ? null : judgments.stream().mapToInt(Judgment::answer).average().orElseThrow(),
                    judgments.isEmpty() ? null : judgments.stream().mapToInt(Judgment::citations).average().orElseThrow(),
                    distribution(completed.stream().map(row -> row.timing().totalMs()).toList()),
                    distribution(completed.stream().map(row -> row.timing().modelTtftMs()).toList()),
                    distribution(completed.stream().map(row -> row.timing().endToEndTtftMs()).toList()),
                    distribution(completed.stream().map(row -> row.estimatedInputTokens() == null ? null : row.estimatedInputTokens().doubleValue()).toList()),
                    new Rate(coverage.size(), hits, coverage.isEmpty() ? null : (double) hits / coverage.size()));
        }).toList();
    }
    public static Distribution distribution(List<Double> values) {
        var sorted = values.stream().filter(java.util.Objects::nonNull).filter(Double::isFinite).sorted().toList();
        int size = sorted.size(); if (size == 0) return new Distribution(0, null, null);
        double median = size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
        return new Distribution(size, median, sorted.get((int) Math.ceil(size * .95) - 1));
    }
    private static int count(List<Result> rows, Outcome outcome) { return (int) rows.stream().filter(row -> row.outcome() == outcome).count(); }
}
