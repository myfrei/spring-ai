package dev.myfrei.repopilot.evaluation;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
class ComparisonSummaryTest {
    @Test void excludesErrorsAndNoModelFromLatencyButCountsTheirOutcomes() {
        var run = EvaluationFixtures.run(List.of(EvaluationFixtures.row(0, Mode.RAG, Outcome.COMPLETED, 100),
                EvaluationFixtures.row(1, Mode.RAG, Outcome.FAILED, 1), EvaluationFixtures.row(2, Mode.RAG, Outcome.NO_EVIDENCE, 2)), List.of(), Map.of());
        var group = ComparisonSummary.summarize(run).get(2);
        assertThat(group.attempted()).isEqualTo(3); assertThat(group.failed()).isEqualTo(1); assertThat(group.noEvidence()).isEqualTo(1);
        assertThat(group.totalMs()).isEqualTo(new ComparisonSummary.Distribution(1, 100.0, 100.0));
        assertThat(group.modelTtftMs().n()).isEqualTo(1); assertThat(group.meanAnswerScore()).isNull(); assertThat(group.judged()).isZero();
    }
    @Test void warmupsAndUnjudgedRowsDoNotFabricateQuality() {
        var rows = List.of(EvaluationFixtures.row(0, Mode.CHAT, Outcome.COMPLETED, 10), EvaluationFixtures.row(1, Mode.CHAT, Outcome.COMPLETED, 20));
        var run = EvaluationFixtures.run(rows, List.of(EvaluationFixtures.row(-1, Mode.CHAT, Outcome.COMPLETED, 99999)), Map.of(0, new Judgment(2, 1, null, "manual", "date")));
        var group = ComparisonSummary.summarize(run).getFirst();
        assertThat(group.attempted()).isEqualTo(2); assertThat(group.totalMs().median()).isEqualTo(15);
        assertThat(group.judged()).isEqualTo(1); assertThat(group.meanAnswerScore()).isEqualTo(2); assertThat(group.meanCitationScore()).isEqualTo(1);
    }
    @Test void distributionHasExplicitSampleSizeMedianAndNearestRankP95() {
        assertThat(ComparisonSummary.distribution(List.of())).isEqualTo(new ComparisonSummary.Distribution(0, null, null));
        assertThat(ComparisonSummary.distribution(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(new ComparisonSummary.Distribution(4, 2.5, 4.0));
        assertThat(ComparisonSummary.distribution(java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> (double) i).toList()).p95()).isEqualTo(19);
    }
    @Test void markdownExportsRawFailuresAndUsesSafeFencesForModelText() {
        var row = new Result(0, "dev-01", 1, Mode.CHAT, "Q", Outcome.FAILED, "```\n<script>alert(1)</script>", "MODEL_ERROR",
                new Timing(2, 1, 0, 1.0, null, null), null, null, null, 0, List.of(), null, false, "");
        String report = MarkdownReport.render(EvaluationFixtures.run(List.of(row), List.of(), Map.of()));
        assertThat(report).contains("MODEL_ERROR", "не выполнена", "NOT_MEASURED", "Исходные результаты", "<script>");
        assertThat(report).contains("````text");
    }
}
