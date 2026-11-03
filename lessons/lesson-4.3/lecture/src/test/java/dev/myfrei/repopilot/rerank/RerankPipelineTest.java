package dev.myfrei.repopilot.rerank;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import dev.myfrei.repopilot.query.QueryRagPipeline;
import dev.myfrei.repopilot.rag.*;
import tools.jackson.databind.json.JsonMapper;
import static dev.myfrei.repopilot.rerank.RerankRequest.Strategy.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RerankPipelineTest {
    private RerankPipeline pipeline(QueryRagPipeline queries,RerankFixtures.FakeRerankModel model) {
        return new RerankPipeline(queries,new RerankService(model,"listwise-v1"),RerankFixtures.planner());
    }
    @Test void rerankAndKeepRunBeforeFinalBudgetSoOversizedFirstHitDoesNotBlockUsefulChunk() {
        var found=RerankFixtures.found();
        var huge=new RagSearchResult(found.manifest(),List.of(RerankFixtures.hit(1,"irrelevant ".repeat(15000),.99),found.hits().get(2)));
        var queries=mock(QueryRagPipeline.class); when(queries.retrieve(any())).thenReturn(RerankFixtures.retrieved(huge));
        var pipeline=pipeline(queries,new RerankFixtures.FakeRerankModel());
        assertThatThrownBy(()->pipeline.prepare(new RerankRequest(RerankFixtures.query(),NONE,1)))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("CONTEXT_TOO_LARGE");
        var ranked=pipeline.prepare(new RerankRequest(RerankFixtures.query(),LEXICAL,1));
        assertThat(ranked.report().rag().sources()).extracting(RagSource::chunkId).containsExactly(found.hits().get(2).source().chunkId());
        assertThat(ranked.rag().baseMessages().getLast().getText()).isEqualTo(RerankFixtures.QUESTION);
    }
    @Test void callRetrievesOnceAndGeneratesExactlyOnceFromSelectedSnapshotWithOriginalQuestion() {
        var queries=mock(QueryRagPipeline.class); when(queries.retrieve(any())).thenReturn(RerankFixtures.retrieved(RerankFixtures.found()));
        var reranker=new RerankFixtures.FakeRerankModel(); var chat=new RerankFixtures.RecordingChat();
        var reply=new RerankCallService(pipeline(queries,reranker),ChatClient.builder(chat)).call(new RerankRequest(RerankFixtures.query(),LEXICAL,1));
        assertThat(reply.outcome()).isEqualTo("COMPLETED"); assertThat(chat.calls).isOne(); verify(queries,times(1)).retrieve(any());
        assertThat(chat.received.getUserMessage().getText()).startsWith("Вопрос: "+RerankFixtures.QUESTION+"\n");
        assertThat(reply.preparation().rag().sources()).hasSize(1); assertThat(reply.providerInputTokens()).isEqualTo(19);
        assertThat(reranker.calls).isZero();
    }
    @Test void noEvidenceHasNeitherRerankingInferenceNorFinalGeneration() {
        var queries=mock(QueryRagPipeline.class); var empty=new RagSearchResult(RerankFixtures.found().manifest(),List.of());
        when(queries.retrieve(any())).thenReturn(RerankFixtures.retrieved(empty)); var reranker=new RerankFixtures.FakeRerankModel(); var chat=new RerankFixtures.RecordingChat();
        var reply=new RerankCallService(pipeline(queries,reranker),ChatClient.builder(chat)).call(new RerankRequest(RerankFixtures.query(),LLM,4));
        assertThat(reply.outcome()).isEqualTo("NO_EVIDENCE"); assertThat(chat.calls).isZero(); assertThat(reranker.calls).isZero(); assertThat(reranker.tags).isZero();
        assertThat(reply.providerInputTokens()).isNull();
    }
    @Test void publicReportDoesNotSerializeCandidateTextOrMessagesOrModelJson() {
        var queries=mock(QueryRagPipeline.class); when(queries.retrieve(any())).thenReturn(RerankFixtures.retrieved(RerankFixtures.found()));
        var report=pipeline(queries,new RerankFixtures.FakeRerankModel()).prepare(new RerankRequest(RerankFixtures.query(),LLM,2)).report();
        String json=JsonMapper.builder().build().writeValueAsString(report);
        assertThat(json).contains("chunkId","textHash","inferenceAttempts").doesNotContain("RAW-SECRET-CANDIDATE","baseMessages","references","\"text\":","\"json\":");
    }
    @Test void sharedReportsDoNotDoubleCountRetrievalOrRunItAgain() {
        var queries=mock(QueryRagPipeline.class); var retrieved=RerankFixtures.retrieved(RerankFixtures.found());
        var pipeline=pipeline(queries,new RerankFixtures.FakeRerankModel());
        var baseline=pipeline.prepareShared(new RerankRequest(RerankFixtures.query(),NONE,2),retrieved);
        var ranked=pipeline.prepareShared(new RerankRequest(RerankFixtures.query(),LEXICAL,2),retrieved);
        assertThat(baseline.report().sharedRetrieval()).isTrue(); assertThat(ranked.report().sharedRetrieval()).isTrue();
        assertThat(baseline.report().searches()).isZero(); assertThat(ranked.report().retrievalMs()).isZero(); verifyNoInteractions(queries);
        assertThat(retrieved.retrieval().hits()).hasSize(3);
    }
    @Test void incompleteBlankAndUnavailableFinalAnswerAreNeverReportedAsSuccessful() {
        for (String failure:List.of("length","blank","offline")) {
            var queries=mock(QueryRagPipeline.class); when(queries.retrieve(any())).thenReturn(RerankFixtures.retrieved(RerankFixtures.found()));
            var chat=new RerankFixtures.RecordingChat();
            if (failure.equals("length")) chat.finish="length";
            if (failure.equals("blank")) chat.answer=" ";
            if (failure.equals("offline")) chat.failure=new IllegalStateException("RAW-SECRET body");
            String expected=switch (failure) { case "length"->"UPSTREAM_INCOMPLETE"; case "blank"->"EMPTY_RESPONSE"; default->"RERANK_GENERATION_UNAVAILABLE"; };
            var service=new RerankCallService(pipeline(queries,new RerankFixtures.FakeRerankModel()),ChatClient.builder(chat));
            assertThatThrownBy(()->service.call(new RerankRequest(RerankFixtures.query(),NONE,1))).isInstanceOf(RagProblem.class).extracting("code").isEqualTo(expected);
            assertThat(chat.calls).isOne();
        }
    }
}
