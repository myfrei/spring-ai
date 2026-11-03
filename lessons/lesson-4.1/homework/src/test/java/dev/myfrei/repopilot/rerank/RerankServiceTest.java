package dev.myfrei.repopilot.rerank;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.rag.*;
import static dev.myfrei.repopilot.rerank.RerankRequest.Strategy.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RerankServiceTest {
    @Test void noneKeepsBaselinePrefixAndEverySourceFieldWithoutTouchingModel() {
        var model=new RerankFixtures.FakeRerankModel(); var original=RerankFixtures.found();
        var decision=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,original,NONE,2);
        assertThat(decision.retrieval().hits()).containsExactlyElementsOf(original.hits().subList(0,2));
        assertThat(decision.retrieval().manifest()).isEqualTo(original.manifest());
        assertThat(decision.report().ranking()).allSatisfy(rank->assertThat(rank.score()).isNull());
        assertThat(model.calls).isZero(); assertThat(model.tags).isZero(); assertThat(original.hits()).hasSize(3);
        assertThatThrownBy(()->decision.retrieval().hits().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void lexicalUsesUnicodeNormalizationAndBaselineTieOrder() {
        var model=new RerankFixtures.FakeRerankModel(); var service=new RerankService(model,"listwise-v1");
        var match=service.rank("ПОРТ taskshelf",RerankFixtures.found(),LEXICAL,1);
        assertThat(match.retrieval().hits().getFirst().source().chunkId()).isEqualTo(RerankFixtures.found().hits().get(2).source().chunkId());
        assertThat(match.report().ranking().getFirst().score()).isEqualTo(1.0);
        assertThat(match.report().ranking().getFirst().cosine()).isEqualTo(.8);
        var ties=service.rank("Java",RerankFixtures.found(),LEXICAL,2);
        assertThat(ties.report().ranking()).extracting(RerankReport.Rank::originalRank).containsExactly(1,2,3);
        assertThat(ties.report().ranking().get(1).score()).isEqualTo(1.0);
        assertThat(model.calls).isZero(); assertThat(model.tags).isZero();
    }
    @Test void oneCompleteListwiseCallReordersBeforeKeepAndRelabelsWithoutChangingTextOrIdentity() {
        var model=new RerankFixtures.FakeRerankModel(); var original=RerankFixtures.found();
        var result=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,original,LLM,1);
        assertThat(result.report().effectiveStrategy()).isEqualTo(LLM); assertThat(result.report().inferenceAttempts()).isOne();
        assertThat(result.report().providerInputTokens()).isEqualTo(31); assertThat(result.report().providerOutputTokens()).isEqualTo(17);
        assertThat(result.report().ranking()).extracting(RerankReport.Rank::originalRank).containsExactly(3,2,1);
        var selected=result.retrieval().hits().getFirst(); var source=original.hits().get(2).source();
        assertThat(selected.text()).isEqualTo(original.hits().get(2).text());
        assertThat(selected.source()).isEqualTo(new RagSource("S1",source.chunkId(),source.documentId(),source.path(),source.title(),source.section(),source.startLine(),source.endLine(),source.textHash(),source.score()));
        assertThat(model.calls).isOne(); assertThat(model.tags).isEqualTo(2);
        assertThatThrownBy(()->result.report().ranking().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void malformedOutputFallsBackToSameBaselineAndRetainsActualUsageWithoutRetry() {
        var model=new RerankFixtures.FakeRerankModel(); model.json="RAW-SECRET-INVALID JSON";
        var result=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,RerankFixtures.found(),LLM,2);
        assertThat(result.report().effectiveStrategy()).isEqualTo(NONE);
        assertThat(result.report().notes()).containsExactly("RERANK_FALLBACK","INVALID_RERANK_OUTPUT");
        assertThat(result.retrieval().hits()).containsExactlyElementsOf(RerankFixtures.found().hits().subList(0,2));
        assertThat(result.report().providerOutputTokens()).isEqualTo(17); assertThat(model.calls).isOne();
        assertThat(result.report().notes().toString()).doesNotContain("RAW-SECRET");
    }
    @Test void incompleteAndTransportErrorAreFallbacksWithOneAttemptAndPostGuard() {
        for (boolean transport:List.of(false,true)) {
            var model=new RerankFixtures.FakeRerankModel();
            if (transport) model.failure=new IllegalStateException("RAW-SECRET transport body"); else model.finish="length";
            var result=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,RerankFixtures.found(),LLM,2);
            assertThat(result.report().effectiveStrategy()).isEqualTo(NONE); assertThat(result.report().inferenceAttempts()).isOne();
            assertThat(result.report().notes()).contains(transport?"RERANK_MODEL_UNAVAILABLE":"RERANK_INCOMPLETE");
            assertThat(model.calls).isOne(); assertThat(model.tags).isEqualTo(2);
        }
    }
    @Test void budgetRejectionHasZeroInferenceAttemptsAndPreservesBudgetReason() {
        var model=new RerankFixtures.FakeRerankModel(); model.estimated=4000;
        var result=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,RerankFixtures.found(),LLM,2);
        assertThat(result.report().notes()).containsExactly("RERANK_FALLBACK","RERANK_CONTEXT_TOO_LARGE");
        assertThat(result.report().inferenceAttempts()).isZero(); assertThat(model.calls).isZero();
        assertThat(result.report().estimatedInputTokens()).isEqualTo(4000); assertThat(result.report().inputBudget()).isEqualTo(3072);
    }
    @Test void digestDriftAbortsEvenWhenModelOutputWasInvalidOrInvocationFailed() {
        for (boolean transport:List.of(false,true)) {
            var model=new RerankFixtures.FakeRerankModel(); model.afterDigest="b".repeat(64);
            if (transport) model.failure=new IllegalStateException("offline"); else model.json="invalid";
            assertThatThrownBy(()->new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,RerankFixtures.found(),LLM,2))
                    .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("RERANK_MODEL_CHANGED");
            assertThat(model.calls).isOne(); assertThat(model.tags).isEqualTo(2);
        }
    }
    @Test void zeroCandidatesSkipsDigestBudgetAndScoringEntirely() {
        var model=new RerankFixtures.FakeRerankModel(); var empty=new RagSearchResult(RerankFixtures.found().manifest(),List.of());
        var result=new RerankService(model,"listwise-v1").rank("Q",empty,LLM,4);
        assertThat(result.report().notes()).containsExactly("NO_CANDIDATES"); assertThat(result.report().candidateCount()).isZero();
        assertThat(result.retrieval().hits()).isEmpty(); assertThat(model.calls).isZero(); assertThat(model.tags).isZero();
    }
    @Test void excessCandidatesAndDuplicateIdentityFailBeforeCallingModel() {
        var model=new RerankFixtures.FakeRerankModel(); var service=new RerankService(model,"listwise-v1"); var base=RerankFixtures.found();
        var excess=new RagSearchResult(base.manifest(),java.util.stream.IntStream.range(1,10).mapToObj(i->RerankFixtures.hit(i,"text",.5)).toList());
        assertThatThrownBy(()->service.rank("Q",excess,LLM,4)).isInstanceOf(IllegalArgumentException.class);
        var duplicate=new RagSearchResult(base.manifest(),List.of(base.hits().getFirst(),base.hits().getFirst()));
        assertThatThrownBy(()->service.rank("Q",duplicate,LLM,4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(model.calls).isZero(); assertThat(model.tags).isZero();
    }
    @Test void unavailableInitialIdentityFallsBackWithoutStartingInference() {
        var model=mock(RerankModel.class);
        when(model.digest()).thenThrow(new RagProblem("RERANK_MODEL_UNAVAILABLE","private upstream detail"));
        var result=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,RerankFixtures.found(),LLM,2);
        assertThat(result.report().effectiveStrategy()).isEqualTo(NONE); assertThat(result.report().inferenceAttempts()).isZero();
        assertThat(result.report().notes()).containsExactly("RERANK_FALLBACK","RERANK_MODEL_UNAVAILABLE");
        verify(model,never()).budget(anyString(),anyList()); verify(model,never()).score(anyString(),anyList());
    }
    @Test void unavailablePostIdentityDiscardsScoresButRetainsActualAttemptAndUsage() {
        var model=mock(RerankModel.class);
        when(model.digest()).thenReturn("a".repeat(64)).thenThrow(new RagProblem("RERANK_MODEL_UNAVAILABLE","offline"));
        when(model.budget(anyString(),anyList())).thenReturn(new RerankModel.Budget(100,3072));
        when(model.score(anyString(),anyList())).thenReturn(new RerankModel.Reply(RerankFixtures.scores(RerankFixtures.found().hits()),"stop",31,17));
        var result=new RerankService(model,"listwise-v1").rank(RerankFixtures.QUESTION,RerankFixtures.found(),LLM,2);
        assertThat(result.report().effectiveStrategy()).isEqualTo(NONE); assertThat(result.report().inferenceAttempts()).isOne();
        assertThat(result.report().providerOutputTokens()).isEqualTo(17);
        assertThat(result.report().ranking()).extracting(RerankReport.Rank::originalRank).containsExactly(1,2,3);
        verify(model,times(1)).score(anyString(),anyList()); verify(model,times(2)).digest();
    }
}
