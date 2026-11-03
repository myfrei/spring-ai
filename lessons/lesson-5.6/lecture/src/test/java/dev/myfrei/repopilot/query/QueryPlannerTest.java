package dev.myfrei.repopilot.query;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryPlannerTest {
    private final QueryModel model = mock(QueryModel.class);
    private final QueryPlanner planner = new QueryPlanner(model,"assistant-v1","Policy");
    private QueryRequest request(QueryRequest.Mode mode, boolean history) {
        return new QueryRequest("А порт?", mode, history ? List.of(new QueryRequest.Turn("Как запустить TaskShelf?","Через compose.")) : List.of(), null,null);
    }
    private void stable() { when(model.digest()).thenReturn("a".repeat(64)); }
    @Test void directDoesNotCallPreparationModelOrTags() {
        var result=planner.prepare(request(QueryRequest.Mode.DIRECT,true));
        assertThat(result.queries()).containsExactly("А порт?"); assertThat(result.preparationCalls()).isZero(); verifyNoInteractions(model);
    }
    @Test void contextualUsesCompleteHistoryAndPreservesOriginalQuestion() {
        stable(); when(model.contextualize(eq("А порт?"),anyList())).thenReturn("Порт HTTP TaskShelf");
        var request=request(QueryRequest.Mode.CONTEXTUAL,true); var result=planner.prepare(request);
        assertThat(result.queries()).containsExactly("А порт?","Порт HTTP TaskShelf"); assertThat(result.preparationCalls()).isOne();
        verify(model).contextualize("А порт?",request.messages()); verify(model,never()).expand(anyString());
    }
    @Test void contextualWithoutHistoryDoesNotInventContext() {
        stable(); var result=planner.prepare(request(QueryRequest.Mode.CONTEXTUAL,false));
        assertThat(result.effectiveMode()).isEqualTo(QueryRequest.Mode.DIRECT); assertThat(result.notes()).containsExactly("NO_HISTORY_DIRECT");
        verify(model,never()).contextualize(anyString(),anyList()); verify(model,never()).expand(anyString());
    }
    @Test void expandedHasAtMostTwoPreparationCallsAndThreeUniqueSearches() {
        stable(); when(model.contextualize(anyString(),anyList())).thenReturn("Порт TaskShelf");
        when(model.expand("Порт TaskShelf")).thenReturn(List.of("HTTP TaskShelf","Запуск TaskShelf"));
        var result=planner.prepare(request(QueryRequest.Mode.EXPANDED,true));
        assertThat(result.preparationCalls()).isEqualTo(2); assertThat(result.queries()).containsExactly("А порт?","Порт TaskShelf","HTTP TaskShelf");
    }
    @Test void expansionWithoutHistoryCostsOneCallAndDeduplicatesTrimmedVariants() {
        stable(); when(model.expand(anyString())).thenReturn(List.of("  А порт?  ","HTTP порт"));
        var result=planner.prepare(request(QueryRequest.Mode.EXPANDED,false));
        assertThat(result.preparationCalls()).isOne(); assertThat(result.queries()).containsExactly("А порт?","HTTP порт");
    }
    @Test void invalidModelOutputFallsBackWithoutRetryAndNeverLosesOriginal() {
        stable(); when(model.expand(anyString())).thenReturn(List.of("Ответ\nнесколько строк"));
        var result=planner.prepare(request(QueryRequest.Mode.EXPANDED,false));
        assertThat(result.queries()).containsExactly("А порт?"); assertThat(result.notes()).contains("QUERY_PREPARATION_FALLBACK");
        assertThat(result.preparationCalls()).isOne(); verify(model,times(1)).expand(anyString());
    }
    @Test void unavailablePreparationModelFallsBackToDirect() {
        when(model.digest()).thenThrow(new dev.myfrei.repopilot.rag.RagProblem("QUERY_MODEL_UNAVAILABLE","offline"));
        var result=planner.prepare(request(QueryRequest.Mode.EXPANDED,false));
        assertThat(result.effectiveMode()).isEqualTo(QueryRequest.Mode.DIRECT); assertThat(result.preparationCalls()).isZero();
        assertThat(result.notes()).containsExactly("QUERY_PREPARATION_FALLBACK","QUERY_MODEL_UNAVAILABLE");
    }
    @Test void changingDigestAbortsInsteadOfReportingSuccessfulComparison() {
        when(model.digest()).thenReturn("a".repeat(64),"b".repeat(64)); when(model.expand(anyString())).thenReturn(List.of("HTTP порт","Запуск"));
        assertThatThrownBy(()->planner.prepare(request(QueryRequest.Mode.EXPANDED,false))).extracting("code").isEqualTo("QUERY_MODEL_CHANGED");
    }
    @Test void promptIdentityChangesWithPolicyOrVersionAndReportsAreImmutable() {
        var first=planner.prepare(request(QueryRequest.Mode.DIRECT,false));
        var second=new QueryPlanner(model,"assistant-v2","New policy").prepare(request(QueryRequest.Mode.DIRECT,false));
        assertThat(first.systemPromptHash()).hasSize(64).isNotEqualTo(second.systemPromptHash()); assertThat(second.promptVersion()).isEqualTo("assistant-v2");
        assertThatThrownBy(()->first.queries().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void requestRejectsOversizedOrIncompleteHistoryAndNonFiniteThreshold() {
        assertThatThrownBy(()->new QueryRequest.Turn("Q"," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new QueryRequest("Q",null,List.of(new QueryRequest.Turn("q","a"),new QueryRequest.Turn("q","a"),new QueryRequest.Turn("q","a")),null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new QueryRequest("Q",null,List.of(),null,Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void budgetRejectionFallsBackBeforeCountingOrInvokingPreparation() {
        stable(); doThrow(new dev.myfrei.repopilot.rag.RagProblem("QUERY_CONTEXT_TOO_LARGE","budget"))
                .when(model).validateContextualize(anyString(),anyList());
        var result=planner.prepare(request(QueryRequest.Mode.EXPANDED,true));
        assertThat(result.effectiveMode()).isEqualTo(QueryRequest.Mode.DIRECT);
        assertThat(result.queries()).containsExactly("А порт?"); assertThat(result.preparationCalls()).isZero();
        assertThat(result.notes()).containsExactly("QUERY_PREPARATION_FALLBACK","QUERY_CONTEXT_TOO_LARGE");
        verify(model,never()).contextualize(anyString(),anyList()); verify(model,never()).expand(anyString());
        verify(model,times(2)).digest();
    }
    @Test void digestDriftStillAbortsAfterInvalidExpansion() {
        when(model.digest()).thenReturn("a".repeat(64),"b".repeat(64));
        when(model.expand(anyString())).thenReturn(List.of("invalid\nformat"));
        assertThatThrownBy(()->planner.prepare(request(QueryRequest.Mode.EXPANDED,false)))
                .extracting("code").isEqualTo("QUERY_MODEL_CHANGED");
        verify(model,times(2)).digest();
    }
    @Test void digestDriftStillAbortsAfterInvocationFailure() {
        when(model.digest()).thenReturn("a".repeat(64),"b".repeat(64));
        when(model.expand(anyString())).thenThrow(new IllegalStateException("connection lost"));
        assertThatThrownBy(()->planner.prepare(request(QueryRequest.Mode.EXPANDED,false)))
                .extracting("code").isEqualTo("QUERY_MODEL_CHANGED");
        verify(model,times(2)).digest();
    }
}
