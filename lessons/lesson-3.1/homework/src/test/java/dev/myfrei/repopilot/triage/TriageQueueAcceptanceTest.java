package dev.myfrei.repopilot.triage;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
class TriageQueueAcceptanceTest {
 private Row row(int ordinal,String id,Mode mode,Category category){return new Row(ordinal,id,mode,Outcome.COMPLETED,new Finding(category,"label",List.of("fact"),"hypothesis","experiment"),"",false,"","","");}
 private DiagnosticReport report(Row...rows){return new DiagnosticReport(UUID.randomUUID(),"EXPERIMENT","rules",true,List.of(rows),"");}
 @Test void groupsBeforeApplyingLimit(){
  var q=TriageQueue.build(report(row(4,"dev-01",Mode.RAG,Category.RETRIEVAL_MISS),row(1,"dev-01",Mode.RAG,Category.RETRIEVAL_MISS),row(7,"dev-02",Mode.RAG,Category.GENERATION_REVIEW)),1);
  assertThat(q.totalGroups()).isEqualTo(2);assertThat(q.totalRows()).isEqualTo(3);assertThat(q.items()).hasSize(1);assertThat(q.items().getFirst().ordinals()).containsExactly(1,4);
 }
 @Test void keepsModesAndCategoriesSeparate(){assertThat(TriageQueue.build(report(row(0,"dev-01",Mode.RAG,Category.RETRIEVAL_MISS),row(1,"dev-01",Mode.FULL_CONTEXT,Category.DATA_OR_CONTEXT_REVIEW),row(2,"dev-01",Mode.RAG,Category.GENERATION_REVIEW)),10).totalGroups()).isEqualTo(3);}
 @Test void healthyAndBaselineAreNotIncidents(){var q=TriageQueue.build(report(row(0,"dev-01",Mode.CHAT,Category.BASELINE_WITHOUT_DOCUMENTS),row(1,"dev-01",Mode.RAG,Category.NO_DETECTED_ISSUE)),10);assertThat(q.items()).isEmpty();assertThat(q.totalRows()).isZero();}
 @Test void priorityIsExplicit(){var q=TriageQueue.build(report(row(0,"dev-01",Mode.RAG,Category.MANUAL_REVIEW_NEEDED),row(1,"dev-02",Mode.RAG,Category.GENERATION_REVIEW),row(2,"dev-03",Mode.RAG,Category.CONTEXT_DROPPED),row(3,"dev-04",Mode.RAG,Category.EXECUTION_FAILURE)),10);assertThat(q.items()).extracting(TriageQueue.Item::priority).containsExactly(0,1,2,3);}
 @Test void stableOrderDoesNotDependOnInputOrder(){var a=row(1,"dev-02",Mode.RAG,Category.RETRIEVAL_MISS);var b=row(0,"dev-01",Mode.RAG,Category.CONTEXT_DROPPED);assertThat(TriageQueue.build(report(a,b),10).items()).isEqualTo(TriageQueue.build(report(b,a),10).items());}
 @Test void validatesBounds(){for(int limit:new int[]{0,-1,21})assertThatThrownBy(()->TriageQueue.build(report(),limit)).isInstanceOf(IllegalArgumentException.class);}
 @Test void doesNotMutateInputAndReturnsImmutableItems(){var input=report(row(9,"dev-01",Mode.RAG,Category.RETRIEVAL_MISS),row(2,"dev-01",Mode.RAG,Category.RETRIEVAL_MISS));var q=TriageQueue.build(input,1);assertThat(q.items().getFirst().ordinals()).containsExactly(2,9);assertThat(input.rows()).extracting(Row::ordinal).containsExactly(9,2);assertThatThrownBy(()->q.items().clear()).isInstanceOf(UnsupportedOperationException.class);}
}
