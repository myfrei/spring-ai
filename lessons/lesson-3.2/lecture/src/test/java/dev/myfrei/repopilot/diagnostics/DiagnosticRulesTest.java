package dev.myfrei.repopilot.diagnostics;

import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.Category;
import static org.assertj.core.api.Assertions.*;
class DiagnosticRulesTest {
    @Test void distinguishesRetrievalMissFromSelectionLoss() {
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, false, false), DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.RETRIEVAL_MISS);
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, false), DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.CONTEXT_DROPPED);
    }
    @Test void providedEvidenceAndBadManualAnswerIsHypothesisNotProofOfModelThinking() {
        var finding = DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, true), DiagnosticFixtures.entry(), DiagnosticFixtures.judgment(0, 0));
        assertThat(finding.category()).isEqualTo(Category.GENERATION_REVIEW);
        assertThat(finding.hypothesis()).contains("Внутреннее решение модели не наблюдается"); assertThat(finding.nextExperiment()).contains("одним изменением");
    }
    @Test void citationsAreCheckedSeparatelyFromCorrectAnswer() {
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, true), DiagnosticFixtures.entry(), DiagnosticFixtures.judgment(2, 0)).category()).isEqualTo(Category.CITATION_REVIEW);
    }
    @Test void missingManualJudgmentNeverBecomesPositiveQuality() {
        var row = DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, true, true);
        assertThat(DiagnosticRules.classify(row, DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.MANUAL_REVIEW_NEEDED);
        assertThat(DiagnosticRules.classify(row, DiagnosticFixtures.entry(), DiagnosticFixtures.judgment(2, 2)).category()).isEqualTo(Category.NO_DETECTED_ISSUE);
    }
    @Test void executionFailureTakesPriorityOverApparentEvidenceAndManualGrade() {
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.FAILED, true, true), DiagnosticFixtures.entry(), DiagnosticFixtures.judgment(2, 2)).category()).isEqualTo(Category.EXECUTION_FAILURE);
    }
    @Test void baselineAndUnknownAreNotMislabelledRetrievalFailures() {
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.CHAT, Outcome.COMPLETED, null, false), DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.BASELINE_WITHOUT_DOCUMENTS);
        var unknown = DiagnosticFixtures.dataset().cases(Split.DEV).stream().filter(entry -> !entry.answerable()).findFirst().orElseThrow();
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.NO_EVIDENCE, false, false), unknown, null).category()).isEqualTo(Category.MANUAL_REVIEW_NEEDED);
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, null, null), unknown, DiagnosticFixtures.judgment(0, 2)).category()).isEqualTo(Category.UNSUPPORTED_ANSWER_REVIEW);
    }
    @Test void contradictoryAndMissingSignalsRequireDataReview() {
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, false, true), DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.INCONSISTENT_EVIDENCE);
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.RAG, Outcome.COMPLETED, null, true), DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.DATA_OR_CONTEXT_REVIEW);
        assertThat(DiagnosticRules.classify(DiagnosticFixtures.row(Mode.FULL_CONTEXT, Outcome.COMPLETED, null, false), DiagnosticFixtures.entry(), null).category()).isEqualTo(Category.DATA_OR_CONTEXT_REVIEW);
    }
}
