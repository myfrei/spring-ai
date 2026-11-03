package dev.myfrei.repopilot.evaluation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
class EvaluationDatasetTest {
    @Test void hasVersionedDisjointTwentyDevTenHoldoutAndRequiredCategories() {
        var dataset = EvaluationFixtures.dataset();
        assertThat(dataset.version()).matches("[0-9a-f]{64}");
        assertThat(dataset.cases(Split.DEV)).hasSize(20); assertThat(dataset.cases(Split.HOLDOUT)).hasSize(10);
        assertThat(dataset.cases(Split.DEV)).extracting(EvaluationCase::kind).contains("FACT", "MULTI", "UNKNOWN", "CONFLICT", "LONG_INPUT");
        assertThat(dataset.cases(Split.HOLDOUT)).allSatisfy(entry -> assertThat(entry.question().length()).isBetween(1, 4000));
    }
    @Test void refusesCrossSplitIdsAndUnknownCasesBeforeInference() {
        var dataset = EvaluationFixtures.dataset();
        assertThatThrownBy(() -> dataset.select(new Request(Split.HOLDOUT, List.of("dev-01"), null, 1, null, null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dataset.select(new Request(Split.DEV, List.of("dev-99"), null, 1, null, null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThat(dataset.select(EvaluationFixtures.request())).extracting(EvaluationCase::id).containsExactly("dev-01", "dev-02");
    }
    @Test void evidenceGroupsRequireCompleteConjunctionAndAllowAlternatives() {
        var entry = new EvaluationCase("dev-01", Split.DEV, "MULTI", "Q", "Gold", List.of(
                List.of(new EvaluationCase.Evidence("a", "port 8091"), new EvaluationCase.Evidence("b", "local")),
                List.of(new EvaluationCase.Evidence("c", "all facts"))));
        assertThat(EvidenceCoverage.hit(entry, List.of(new Source("S1", "a", "a.md", "", "port   8091")))).isFalse();
        assertThat(EvidenceCoverage.hit(entry, List.of(new Source("S1", "a", "a.md", "", "port\n8091"), new Source("S2", "b", "b.md", "", "local")))).isTrue();
        assertThat(EvidenceCoverage.hit(entry, List.of(new Source("S1", "c", "c.md", "", "all facts")))).isTrue();
        assertThat(EvidenceCoverage.hit(entry, List.of(new Source("S1", "wrong", "a.md", "", "port 8091 local all facts")))).isFalse();
    }
    @Test void unknownIsNotApplicableAndWholeCorpusContainsEveryAnnotatedEvidence() {
        var corpus = new CorpusCatalog(new DefaultResourceLoader()).snapshot();
        var sources = corpus.documents().stream().map(document -> new Source(document.id(), document.id(), document.path(), "", document.text())).toList();
        for (var split : Split.values()) for (var entry : EvaluationFixtures.dataset().cases(split)) {
            assertThat(EvidenceCoverage.hit(entry, sources)).isEqualTo(entry.answerable() ? true : null);
        }
    }
}
