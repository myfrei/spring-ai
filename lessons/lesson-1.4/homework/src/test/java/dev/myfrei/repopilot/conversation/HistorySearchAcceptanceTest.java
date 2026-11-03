package dev.myfrei.repopilot.conversation;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class HistorySearchAcceptanceTest {
    private final HistorySearch search=new HistorySearch();
    @TempDir Path directory;
    private Turn turn(long id,String conversation,String question,String answer,String status){return new Turn(id,conversation,question,answer,status,"");}
    @Test void searchesUnicodeQuestionAndAnswerLiterallyInNewestFirstOrder() {
        var rows=List.of(turn(1,"own","Как включить ПАМЯТЬ?","answer","completed"),turn(2,"own","Q","память SQLite","failed"),turn(3,"own","Q","other","cancelled"));
        assertThat(search.find(rows,"own","  ПаМяТь  ",10)).extracting(Turn::id).containsExactly(2L,1L);
    }
    @Test void foreignAndPendingTurnsNeverEnterSearchResults() {
        var rows=List.of(turn(1,"other","needle","needle","completed"),turn(2,"own","needle","partial","pending"),turn(3,"own","Q","needle","interrupted"));
        assertThat(search.find(rows,"own","needle",10)).extracting(Turn::id).containsExactly(3L);
    }
    @Test void limitAppliesAfterFilteringAndInputSnapshotIsNotChanged() {
        var rows=new ArrayList<>(List.of(turn(1,"own","Q","hit","completed"),turn(2,"own","Q","miss","completed"),turn(3,"own","Q","hit","completed")));
        var copy=List.copyOf(rows);var result=search.find(rows,"own","hit",1);
        assertThat(result).extracting(Turn::id).containsExactly(3L);assertThat(rows).isEqualTo(copy);
        assertThatThrownBy(()->result.add(copy.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void regexAndSqlWildcardsAreLiteralAndNfcIsSupported() {
        var rows=List.of(turn(1,"own","Q","literal .*_% and café","completed"),turn(2,"own","Q","anything","completed"));
        assertThat(search.find(rows,"own",".*_%",10)).extracting(Turn::id).containsExactly(1L);
        assertThat(search.find(rows,"own","café",10)).extracting(Turn::id).containsExactly(1L);
    }
    @Test void invalidQueriesAndLimitsAreRejected() {
        for(String query:new String[]{"", "  ", "q".repeat(201)})assertThatThrownBy(()->search.find(List.of(),"own",query,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->search.find(List.of(),"own",null,10)).isInstanceOf(IllegalArgumentException.class);
        for(int limit:new int[]{0,-1,21})assertThatThrownBy(()->search.find(List.of(),"own","Q",limit)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actualSqliteSearchDoesNotChangeMemoryWindowOrStoredTurns() {
        var history=new SqliteHistory(directory.resolve("history.db").toString());var conversation=history.create("Search task");String id=conversation.id();
        for(int i=0;i<5;i++){long turn=history.begin(id,"Question "+i);history.append(turn,"answer "+i);history.finish(turn,i==4?"failed":"completed","");}
        var before=history.turns(id);var memoryBefore=new ConversationMemoryFactory(history,3).create(id).get(id);
        var result=new HistorySearchController(history,search).search(UUID.fromString(id),"answer",2);
        assertThat(result).extracting(Turn::status).containsExactly("failed","completed");
        assertThat(history.turns(id)).isEqualTo(before);assertThat(new ConversationMemoryFactory(history,3).create(id).get(id)).isEqualTo(memoryBefore);
    }
}
