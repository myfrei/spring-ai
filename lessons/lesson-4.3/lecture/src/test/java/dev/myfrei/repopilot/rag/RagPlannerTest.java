package dev.myfrei.repopilot.rag;

import java.util.List;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.ingestion.EmbeddingIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import static org.assertj.core.api.Assertions.*;

class RagPlannerTest {
    private final Utf8TokenEstimator estimator = new Utf8TokenEstimator();
    private final RagManifest manifest = new RagManifest("taskshelf", "a".repeat(64), "b".repeat(64),
            new EmbeddingIdentity("embeddinggemma:300m", "c".repeat(64), 3, "embeddinggemma-title-text-v1"), 2);
    private RagPlanner planner(int window) { return new RagPlanner("Policy", new ContextBudget(window, 50, 50), this.estimator); }
    private RagHit hit(String label, int size) {
        return new RagHit(new RagSource(label, "d".repeat(64), "runbook", "RUNBOOK.md", "Runbook", "Start", 1, 4, "e".repeat(64), .9), "x".repeat(size));
    }
    @Test void includesQuestionSourcesAndExactAdvisorExpansionInBudget() {
        var planner = planner(4096);
        var plan = planner.plan("JSON {literal}?", List.of(), new RagSearchResult(manifest, List.of(hit("S1", 100), hit("S2", 100))));
        assertThat(plan.report().status()).isEqualTo("READY");
        assertThat(plan.report().sources()).extracting(RagSource::label).containsExactly("S1", "S2");
        assertThat(planner.projected(plan).getLast().getText()).contains("JSON {literal}?", "[S1] RUNBOOK.md:1–4", "[S2]");
        assertThat(plan.report().estimatedInputTokens()).isEqualTo(estimator.estimate(planner.projected(plan)));
        assertThat(plan.baseMessages().getLast().getText()).isEqualTo("JSON {literal}?");
    }
    @Test void preservesRankedPrefixAndReportsOmittedChunks() {
        var planner = planner(800);
        var plan = planner.plan("Q", List.of(), new RagSearchResult(manifest, List.of(hit("S1", 20), hit("S2", 4000), hit("S3", 1))));
        assertThat(plan.report().sources()).extracting(RagSource::label).containsExactly("S1");
        assertThat(plan.report().omittedChunks()).isEqualTo(2);
        assertThat(plan.report().estimatedInputTokens()).isLessThanOrEqualTo(plan.report().inputBudget());
    }
    @Test void includesNewestCompletePairsAfterSources() {
        var history = List.<Message>of(new UserMessage("q".repeat(10000)), new AssistantMessage("a"), new UserMessage("latest"), new AssistantMessage("reply"));
        var plan = planner(1000).plan("Q", history, new RagSearchResult(manifest, List.of(hit("S1", 30))));
        assertThat(plan.report().includedHistoryTurns()).isEqualTo(1);
        assertThat(plan.report().omittedHistoryTurns()).isEqualTo(1);
        assertThat(plan.baseMessages()).extracting(Message::getText).contains("latest", "reply");
    }
    @Test void noEvidenceHasNoReferencesAndDoesNotPretendToHaveInputUsage() {
        var plan = planner(4096).plan("Q", List.of(), new RagSearchResult(manifest, List.of()));
        assertThat(plan.references()).isEmpty(); assertThat(plan.report().status()).isEqualTo("NO_EVIDENCE");
        assertThat(plan.report().estimatedInputTokens()).isZero();
    }
    @Test void refusesOversizedFirstSourceAndMalformedHistory() {
        assertThatThrownBy(() -> planner(500).plan("Q", List.of(), new RagSearchResult(manifest, List.of(hit("S1", 5000)))))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("CONTEXT_TOO_LARGE");
        assertThatThrownBy(() -> planner(500).plan("Q", List.of(new UserMessage("orphan")), new RagSearchResult(manifest, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
