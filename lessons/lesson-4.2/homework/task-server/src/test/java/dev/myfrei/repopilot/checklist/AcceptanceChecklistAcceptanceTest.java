package dev.myfrei.repopilot.checklist;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AcceptanceChecklistAcceptanceTest {
    private final AcceptanceChecklistPolicy policy=new AcceptanceChecklistPolicy();
    private AcceptanceChecklist build(List<String> lines,int items,int budget){return policy.build(new ChecklistInput("TASK-101",lines,items,budget));}
    @Test void allWholeCriteriaAreReturnedWithSourceIdsAndTruthfulCounts(){
        var result=build(List.of("Тест проходит","Ошибка понятна"),8,100);
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::id).containsExactly("AC-001","AC-002");
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::text).containsExactly("Тест проходит","Ошибка понятна");
        assertThat(result.taskId()).isEqualTo("TASK-101");assertThat(result.eligibleCount()).isEqualTo(2);assertThat(result.omittedCount()).isZero();assertThat(result.complete()).isTrue();assertThat(result.budgetBlocked()).isFalse();
        assertThat(result.usedCodePoints()).isEqualTo("Тест проходитОшибка понятна".codePointCount(0,"Тест проходитОшибка понятна".length()));
    }
    @Test void nfkcWhitespaceAndExactDedupPreserveFirstOriginalIndex(){
        var result=build(List.of(" \t ","  ＡＰＩ\u00a0работает\n корректно  ","API работает корректно","Тест\tпроходит"),8,200);
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::id).containsExactly("AC-002","AC-004");
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::text).containsExactly("API работает корректно","Тест проходит");
        assertThat(result.eligibleCount()).isEqualTo(2);assertThat(result.complete()).isTrue();
    }
    @Test void maxItemsLimitsPrefixAndReportsOmittedWithoutBudgetBlame(){
        var result=build(List.of("Первый","Второй","Третий"),1,200);
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::text).containsExactly("Первый");
        assertThat(result.eligibleCount()).isEqualTo(3);assertThat(result.omittedCount()).isEqualTo(2);assertThat(result.complete()).isFalse();assertThat(result.budgetBlocked()).isFalse();
        var conflict=build(List.of("А".repeat(20),"Б".repeat(30)),1,40);
        assertThat(conflict.items()).hasSize(1);assertThat(conflict.omittedCount()).isEqualTo(1);assertThat(conflict.budgetBlocked()).isFalse();
    }
    @Test void oversizedFirstCriterionIsNeverCutOrReplacedByLaterShortOne(){
        var text="К".repeat(41);var result=build(List.of(text,text,"  ","Короткий"),8,40);
        assertThat(result.items()).isEmpty();assertThat(result.usedCodePoints()).isZero();assertThat(result.eligibleCount()).isEqualTo(2);assertThat(result.omittedCount()).isEqualTo(2);
        assertThat(result.complete()).isFalse();assertThat(result.budgetBlocked()).isTrue();
    }
    @Test void nextOversizedCriterionStopsPrefixEvenIfLaterItemCouldFit(){
        var result=build(List.of("А".repeat(20),"Б".repeat(30),"В"),8,40);
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::id).containsExactly("AC-001");assertThat(result.usedCodePoints()).isEqualTo(20);
        assertThat(result.omittedCount()).isEqualTo(2);assertThat(result.budgetBlocked()).isTrue();
    }
    @Test void unicodeCodePointsAndExactBudgetBoundaryAreUsed(){
        var text="🚀".repeat(40);var result=build(List.of(text),8,40);
        assertThat(result.items()).extracting(AcceptanceChecklist.Item::text).containsExactly(text);assertThat(result.usedCodePoints()).isEqualTo(40);
        assertThat(result.complete()).isTrue();assertThat(result.budgetBlocked()).isFalse();
        var lower=build(List.of("🚀".repeat(41)),8,40);assertThat(lower.items()).isEmpty();assertThat(lower.budgetBlocked()).isTrue();
        var padded=build(List.of("  "+"Ａ".repeat(40)+"   "),8,40);
        assertThat(padded.items()).extracting(AcceptanceChecklist.Item::text).containsExactly("A".repeat(40));assertThat(padded.usedCodePoints()).isEqualTo(40);assertThat(padded.complete()).isTrue();
        var expanded=build(List.of("ﬀ".repeat(21)),8,40);assertThat(expanded.items()).isEmpty();assertThat(expanded.budgetBlocked()).isTrue();
    }
    @Test void emptyEligibleListIsCompleteAndCaseIsNotSilentlyFolded(){
        var empty=build(List.of(" ","\n\t"),8,40);assertThat(empty.items()).isEmpty();assertThat(empty.eligibleCount()).isZero();assertThat(empty.omittedCount()).isZero();assertThat(empty.complete()).isTrue();assertThat(empty.budgetBlocked()).isFalse();
        var caseSensitive=build(List.of("Тест","тест"),8,40);assertThat(caseSensitive.items()).hasSize(2);
    }
    @Test void immutableSnapshotsAndInputBoundsRemainEnforced(){
        var lines=new ArrayList<>(List.of("Первый","Второй"));var input=new ChecklistInput("TASK-101",lines,8,100);lines.clear();
        var result=policy.build(input);assertThat(input.criteria()).containsExactly("Первый","Второй");assertThat(result.items()).hasSize(2);
        assertThatThrownBy(()->result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        for(int bound:List.of(0,9))assertThatIllegalArgumentException().isThrownBy(()->new ChecklistInput("TASK-101",List.of(),bound,40));
        for(int bound:List.of(39,801))assertThatIllegalArgumentException().isThrownBy(()->new ChecklistInput("TASK-101",List.of(),1,bound));
        assertThatIllegalArgumentException().isThrownBy(()->new ChecklistInput("../secret",List.of(),1,40));
        assertThatIllegalArgumentException().isThrownBy(()->new ChecklistInput("TASK-101",Collections.nCopies(21,"x"),1,40));
    }
}
