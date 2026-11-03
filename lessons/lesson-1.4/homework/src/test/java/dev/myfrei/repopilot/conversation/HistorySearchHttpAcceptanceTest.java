package dev.myfrei.repopilot.conversation;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.myfrei.repopilot.chat.ApiExceptionHandler;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class HistorySearchHttpAcceptanceTest {
 @TempDir Path directory;
 @Test void endpointReturnsOnlyMatchingTurnsOfRequestedConversation() throws Exception {
  var history=new SqliteHistory(directory.resolve("history.db").toString());var own=history.create("own");var other=history.create("other");
  long id=history.begin(own.id(),"Q");history.append(id,"Needle");history.finish(id,"completed","");
  long foreign=history.begin(other.id(),"Q");history.append(foreign,"Needle");history.finish(foreign,"completed","");
  var mvc=MockMvcBuilders.standaloneSetup(new HistorySearchController(history,new HistorySearch())).setControllerAdvice(new ApiExceptionHandler(),new ConversationExceptionHandler()).build();
  mvc.perform(get("/api/conversations/"+own.id()+"/search").param("q","needle").param("limit","1"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].conversationId").value(own.id()));
 }
 @Test void missingConversationIs404AndBadLimitIs400() throws Exception {
  var history=new SqliteHistory(directory.resolve("history.db").toString());var own=history.create("own");
  var mvc=MockMvcBuilders.standaloneSetup(new HistorySearchController(history,new HistorySearch())).setControllerAdvice(new ApiExceptionHandler(),new ConversationExceptionHandler()).build();
  mvc.perform(get("/api/conversations/"+UUID.randomUUID()+"/search").param("q","needle")).andExpect(status().isNotFound());
  mvc.perform(get("/api/conversations/"+own.id()+"/search").param("q","needle").param("limit","99")).andExpect(status().isBadRequest());
 }
}
