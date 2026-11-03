package dev.myfrei.repopilot.progress;
import java.util.*;
import dev.myfrei.repopilot.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
class GuardedAgentAdapterTest {
 @Test void guardedRouteSharesTheStoreButDoesNotChangeTheBaselineEngine(){
  var model=new Model(List.of(calls(1),calls(2),answer()));var store=new AgentStore();var engine=engine(model,store);var id=store.createConversation().id();
  var rule=new CycleStopRule(){@Override public boolean afterTool(List<ToolObservation> history){return history.size()>=2;}};
  var run=new GuardedAgentController(engine,rule).run(id,new AgentRunRequest("Read twice"));
  assertThat(run.stopReason()).isEqualTo("NO_PROGRESS");assertThat(run.status()).isEqualTo("STOPPED");assertThat(run.answer()).isNull();assertThat(run.report().modelCalls()).isEqualTo(2);assertThat(run.report().executedTools()).isEqualTo(2);assertThat(store.run(run.id())).isEqualTo(run);assertThat(store.conversation(id).turns()).isEmpty();assertThat(store.conversation(id).activeRunId()).isNull();
  assertThat(store.messages(run.id()).stream().flatMap(m->m.toolResults().stream()).count()).isEqualTo(2);
  var plain=engine.run(id,"Just greet me");assertThat(plain.status()).isEqualTo("COMPLETED");assertThat(store.conversation(id).turns()).hasSize(1);assertThat(model.calls).isEqualTo(3);
 }
 @Test void stoppingMidBatchStillRepliesToEveryRequestedCallWithoutExecutingTheRest(){
  var model=new Model(List.of(batch()));var store=new AgentStore();var rule=new CycleStopRule(){@Override public boolean afterTool(List<ToolObservation> history){return true;}};
  var run=new GuardedAgentController(engine(model,store),rule).run(store.createConversation().id(),new AgentRunRequest("Read"));
  assertThat(run.stopReason()).isEqualTo("NO_PROGRESS");assertThat(run.report().toolCalls()).isEqualTo(2);assertThat(run.report().executedTools()).isEqualTo(1);
  var replies=store.messages(run.id()).stream().flatMap(m->m.toolResults().stream()).toList();assertThat(replies).extracting(AgentMessage.ToolReply::id).containsExactly("call-1","call-2");assertThat(replies.getLast().resultJson()).contains("NOT_EXECUTED");assertThat(model.calls).isEqualTo(1);
 }
 @Test void failedPolicyLeavesAStoppedInspectableRunAndReleasesConversation(){
  var model=new Model(List.of(calls(1),answer()));var store=new AgentStore();var engine=engine(model,store);var id=store.createConversation().id();
  var broken=new CycleStopRule(){@Override public boolean afterTool(List<ToolObservation> history){throw new UnsupportedOperationException("fixture");}};
  var run=new GuardedAgentController(engine,broken).run(id,new AgentRunRequest("Read"));assertThat(run.stopReason()).isEqualTo("STOP_RULE_FAILED");assertThat(store.events(run.id()).getLast().type()).isEqualTo("RUN_FINISHED");assertThat(store.conversation(id).activeRunId()).isNull();assertThat(engine.run(id,"Hello").status()).isEqualTo("COMPLETED");
 }
 private AgentEngine engine(ChatModel model,AgentStore store){return new AgentEngine(model,()->"c".repeat(64),List.of(new AgentTool(){public String name(){return "getProjectInfo";}public String description(){return "Read a fixture";}public String inputSchema(){return "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";}public AgentToolResult execute(String args,AgentToolContext context){return AgentToolResult.success("{\"name\":\"TaskShelf\"}");}}),store,"test-model",4096);}
 private static ChatResponse calls(int id){return response("",List.of(new AssistantMessage.ToolCall("call-"+id,"function","getProjectInfo","{}")));}
 private static ChatResponse batch(){return response("",List.of(new AssistantMessage.ToolCall("call-1","function","getProjectInfo","{}"),new AssistantMessage.ToolCall("call-2","function","getProjectInfo","{}")));}
 private static ChatResponse answer(){return response("Hello",List.of());}
 private static ChatResponse response(String text,List<AssistantMessage.ToolCall> calls){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).toolCalls(calls).build(),ChatGenerationMetadata.builder().finishReason("stop").build())));}
 private static class Model implements ChatModel{private final Queue<ChatResponse> script;int calls;Model(List<ChatResponse> values){script=new ArrayDeque<>(values);}public ChatResponse call(Prompt prompt){calls++;return script.remove();}}
}
