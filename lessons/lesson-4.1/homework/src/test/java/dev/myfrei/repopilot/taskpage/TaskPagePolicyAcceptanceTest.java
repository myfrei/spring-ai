package dev.myfrei.repopilot.taskpage;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskPagePolicyAcceptanceTest {
    private final TaskPagePolicy policy=new TaskPagePolicy();
    private TaskPage.Row row(int id,String status){return new TaskPage.Row("TASK-"+id,"Задача "+id,status);}
    private List<TaskPage.Row> rows(){return List.of(row(103,"DONE"),row(101,"OPEN"),row(105,"OPEN"),row(102,"IN_PROGRESS"),row(104,"OPEN"));}
    @Test void firstPageIsStableSortedAndBounded(){
        var page=policy.page(rows(),"ALL",2,null);
        assertThat(page.tasks()).containsExactly(row(101,"OPEN"),row(102,"IN_PROGRESS"));
        assertThat(page.total()).isEqualTo(5);assertThat(page.returned()).isEqualTo(2);assertThat(page.nextCursor()).isNotBlank();assertThat(page.snapshotVersion()).matches("[0-9a-f]{64}");
    }
    @Test void allPagesContainEachTaskOnceWithNoHiddenRefill(){
        var ids=new ArrayList<String>();String cursor=null;
        for(int i=0;i<3;i++){var page=policy.page(rows(),"ALL",2,cursor);page.tasks().forEach(item->ids.add(item.id()));cursor=page.nextCursor();}
        assertThat(ids).containsExactly("TASK-101","TASK-102","TASK-103","TASK-104","TASK-105");assertThat(cursor).isEmpty();
    }
    @Test void filterAppliesBeforePaginationAndTotalMeansFilteredCount(){
        var first=policy.page(rows(),"OPEN",2,"");
        assertThat(first.total()).isEqualTo(3);assertThat(first.tasks()).extracting(TaskPage.Row::id).containsExactly("TASK-101","TASK-104");
        var last=policy.page(rows(),"OPEN",2,first.nextCursor());assertThat(last.tasks()).extracting(TaskPage.Row::id).containsExactly("TASK-105");assertThat(last.nextCursor()).isEmpty();
    }
    @Test void cursorCannotMoveAcrossFilterOrChangedSnapshot(){
        var page=policy.page(rows(),"ALL",2,null);
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(rows(),"OPEN",2,page.nextCursor()));
        var changed=new ArrayList<>(rows());changed.set(0,new TaskPage.Row("TASK-103","Новая формулировка","DONE"));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(changed,"ALL",2,page.nextCursor()));
        var open=policy.page(rows(),"OPEN",1,null);
        assertThat(open.snapshotVersion()).isEqualTo(page.snapshotVersion());
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(changed,"OPEN",1,open.nextCursor()));
    }
    @Test void inputOrderDoesNotInvalidateCursorButChangedLimitIsAllowed(){
        var original=rows();var first=policy.page(original,"ALL",1,null);var reversed=new ArrayList<>(original);Collections.reverse(reversed);
        var next=policy.page(reversed,"ALL",3,first.nextCursor());
        assertThat(next.snapshotVersion()).isEqualTo(first.snapshotVersion());assertThat(next.tasks()).extracting(TaskPage.Row::id).containsExactly("TASK-102","TASK-103","TASK-104");
    }
    @Test void emptyResultAndLastPageHaveNoNextCursor(){
        var empty=policy.page(List.of(),"ALL",3,null);assertThat(empty.tasks()).isEmpty();assertThat(empty.returned()).isZero();assertThat(empty.total()).isZero();assertThat(empty.nextCursor()).isEmpty();
        var absent=policy.page(List.of(row(101,"DONE")),"OPEN",1,null);assertThat(absent.tasks()).isEmpty();assertThat(absent.nextCursor()).isEmpty();
        var all=policy.page(rows(),"ALL",5,null);assertThat(all.returned()).isEqualTo(5);assertThat(all.nextCursor()).isEmpty();
    }
    @Test void invalidBoundsStatusDuplicateIdsAndMalformedCursorAreRejected(){
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(null,"ALL",2,null));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(rows(),null,2,null));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(Collections.singletonList(null),"ALL",2,null));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(List.of(row(101,"OPEN"),row(103,"DONE"),row(103,"DONE")),"OPEN",2,null));
        for(int limit:List.of(0,-1,6))assertThatIllegalArgumentException().isThrownBy(()->policy.page(rows(),"ALL",limit,null));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(rows(),"open",2,null));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(List.of(row(101,"OPEN"),row(101,"DONE")),"ALL",2,null));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(rows(),"ALL",2,"not-a-valid-cursor"));
        var many=new ArrayList<TaskPage.Row>();for(int i=100;i<201;i++)many.add(row(i,"OPEN"));
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(many,"ALL",2,null));
        String version=TaskPageCursor.version(rows().stream().sorted(Comparator.comparing(TaskPage.Row::id)).toList());
        assertThatIllegalArgumentException().isThrownBy(()->policy.page(rows(),"ALL",2,TaskPageCursor.encode(version,"ALL",5)));
    }
    @Test void inputAndOutputAreImmutableSnapshots(){
        var input=new ArrayList<>(rows());var copy=List.copyOf(input);var page=policy.page(input,"ALL",2,null);
        assertThat(input).containsExactlyElementsOf(copy);input.clear();assertThat(page.tasks()).hasSize(2);
        assertThatThrownBy(()->page.tasks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
