package dev.myfrei.repopilot.mcphost;
import java.util.*;
import dev.myfrei.repopilot.rag.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class McpHostEngineTest {
 @Test void workflowReadsRemoteThenRetrievesOnceAndFinalCallHasSnapshotButNoTools(){
  var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();model.script.add(HostFixtures.answer());var identity=new HostFixtures.Identity();
  var reply=HostFixtures.engine(gateway,prep,model,identity,4096).workflow(HostFixtures.request());
  assertThat(reply.outcome()).isEqualTo("COMPLETED");assertThat(gateway.calls).isOne();assertThat(prep.calls).isOne();assertThat(model.calls).isOne();assertThat(identity.calls).isEqualTo(2);
  assertThat(reply.report().selection().attempts()).isZero();assertThat(reply.report().generation().attempts()).isOne();assertThat(reply.report().queryEmbeddingAttempts()).isOne();assertThat(reply.report().generation().providerInputTokens()).isEqualTo(8);
  String prompt=model.prompts.getFirst().getContents();assertThat(prompt).contains(HostFixtures.QUESTION,"TASK-101","Найденные справочные фрагменты","[S1]");assertThat(prompt.indexOf("Найденные справочные фрагменты")).isEqualTo(prompt.lastIndexOf("Найденные справочные фрагменты"));
  assertThat(((OllamaChatOptions)model.prompts.getFirst().getOptions()).getToolCallbacks()).isNullOrEmpty();assertFinal(model.prompts.getFirst().getOptions());
  String report=JsonMapper.builder().build().writeValueAsString(reply.report());assertThat(report).doesNotContain("PRIVATE-QUESTION","acceptanceCriteria","summary","responseData","arguments");assertThat(reply.report().envelopeHash()).hasSize(64);
 }
 @Test void actualSdkReturnDirectSelectsOneRemoteThenIndependentFinalCall(){
  var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();model.script.addAll(List.of(HostFixtures.select(),HostFixtures.answer()));var identity=new HostFixtures.Identity();
  var reply=HostFixtures.engine(gateway,prep,model,identity,4096).model(HostFixtures.request());
  assertThat(model.calls).isEqualTo(2);assertThat(gateway.calls).isOne();assertThat(prep.calls).isOne();assertThat(identity.calls).isEqualTo(4);
  assertThat(reply.report().selection().attempts()).isOne();assertThat(reply.report().generation().attempts()).isOne();assertThat(reply.report().selectionToolCalls()).isOne();assertThat(reply.report().selection().providerInputTokens()).isEqualTo(8);assertThat(reply.report().generation().providerInputTokens()).isEqualTo(8);
  var options=(OllamaChatOptions)model.prompts.getFirst().getOptions();assertThat(options.getNumPredict()).isEqualTo(384);assertThat(options.getTemperature()).isEqualTo(0.0);assertThat(options.getNumCtx()).isEqualTo(4096);assertThat(options.getToolCallbacks()).hasSize(1);assertThat(options.getToolCallbacks().getFirst().getToolDefinition().inputSchema()).contains("TASK-101").doesNotContain("TASK-102");
  assertThat(model.prompts.getFirst().getContents()).doesNotContain("Найденные справочные фрагменты");assertThat(model.prompts.getLast().getInstructions()).noneMatch(m->m instanceof ToolResponseMessage);assertFinal(model.prompts.getLast().getOptions());
 }
 @Test void noEvidenceSkipsFinalAndWorkflowDoesNotNeedModelIdentity(){
  for(boolean selection:List.of(false,true)){var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();prep.empty=true;var model=new HostFixtures.Model();model.script.add(HostFixtures.select());var identity=new HostFixtures.Identity();var engine=HostFixtures.engine(gateway,prep,model,identity,4096);
   var reply=selection?engine.model(HostFixtures.request()):engine.workflow(HostFixtures.request());assertThat(reply.outcome()).isEqualTo("NO_EVIDENCE");assertThat(reply.report().generation().attempts()).isZero();assertThat(model.calls).isEqualTo(selection?1:0);assertThat(identity.calls).isEqualTo(selection?2:0);}
 }
 @Test void noToolUnsupportedToolAndMalformedArgumentsCannotReachRemoteOrRag(){
  for(String mode:List.of("BARE","UNKNOWN","WRONG","EXTRA","DUPLICATE","TRAILING")){var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();var identity=new HostFixtures.Identity();
   var response=switch(mode){case "BARE"->HostFixtures.answer();case "UNKNOWN"->HostFixtures.response("","stop",List.of(HostFixtures.tool("readFile","{}",1)));default->{String args=switch(mode){case "WRONG"->"{\"taskId\":\"TASK-102\"}";case "EXTRA"->"{\"taskId\":\"TASK-101\",\"command\":\"PRIVATE\"}";case "DUPLICATE"->"{\"taskId\":\"TASK-101\",\"taskId\":\"TASK-101\"}";default->"{\"taskId\":\"TASK-101\"} {}";};yield HostFixtures.response("","stop",List.of(HostFixtures.tool(RemoteReadMetadata.TOOL,args,1)));}};
   model.script.add(response);String expected=mode.equals("BARE")?"HOST_TASK_NOT_READ":mode.equals("UNKNOWN")?"HOST_TOOL_NOT_ALLOWED":"HOST_TOOL_ARGUMENTS";
   assertThatThrownBy(()->HostFixtures.engine(gateway,prep,model,identity,4096).model(HostFixtures.request())).isInstanceOf(McpHostProblem.class).extracting("code").isEqualTo(expected);assertThat(gateway.calls).isZero();assertThat(prep.calls).isZero();assertThat(model.calls).isOne();assertThat(identity.calls).isEqualTo(2);
  }
 }
 @Test void batchToolLimitStopsAfterOneRemoteAndDoesNotProceedToRag(){
  var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();model.script.add(HostFixtures.response("","stop",List.of(HostFixtures.tool(RemoteReadMetadata.TOOL,"{\"taskId\":\"TASK-101\"}",1),HostFixtures.tool(RemoteReadMetadata.TOOL,"{\"taskId\":\"TASK-101\"}",2))));
  assertThatThrownBy(()->HostFixtures.engine(gateway,prep,model,new HostFixtures.Identity(),4096).model(HostFixtures.request())).isInstanceOf(McpHostProblem.class).extracting("code").isEqualTo("HOST_REMOTE_LIMIT");assertThat(gateway.calls).isOne();assertThat(prep.calls).isZero();assertThat(model.calls).isOne();
 }
 @Test void budgetAndUnknownIdRejectBeforeModelOrRemoteAttempts(){
  var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();var identity=new HostFixtures.Identity();
  assertThatThrownBy(()->HostFixtures.engine(gateway,prep,model,identity,700).model(HostFixtures.request())).isInstanceOf(McpHostProblem.class).satisfies(e->{var p=(McpHostProblem)e;assertThat(p.code()).isEqualTo("HOST_CONTEXT_TOO_LARGE");assertThat(p.report().selection().attempts()).isZero();});assertThat(model.calls).isZero();assertThat(gateway.calls).isZero();
  assertThatThrownBy(()->HostFixtures.engine(gateway,prep,model,identity,4096).workflow(new McpHostRequest("TASK-999","Q"))).isInstanceOf(McpHostProblem.class).extracting("code").isEqualTo("HOST_UNKNOWN_TASK");assertThat(gateway.calls).isZero();
 }
 @Test void digestChecksAfterFailuresAndBetweenPhasesAbortWithoutExtraGeneration(){
  var gateway=new HostFixtures.Gateway();var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();model.failure=new IllegalStateException("PRIVATE");var identity=new HostFixtures.Identity();identity.sequence.addAll(List.of(HostFixtures.DIGEST,"b".repeat(64)));
  assertThatThrownBy(()->HostFixtures.engine(gateway,prep,model,identity,4096).model(HostFixtures.request())).isInstanceOf(McpHostProblem.class).extracting("code").isEqualTo("HOST_MODEL_CHANGED");assertThat(model.calls).isOne();assertThat(identity.calls).isEqualTo(2);
  var second=new HostFixtures.Model();second.script.add(HostFixtures.select());var changed=new HostFixtures.Identity();changed.sequence.addAll(List.of(HostFixtures.DIGEST,HostFixtures.DIGEST,"c".repeat(64)));
  assertThatThrownBy(()->HostFixtures.engine(gateway,prep,second,changed,4096).model(HostFixtures.request())).isInstanceOf(McpHostProblem.class).satisfies(e->{var p=(McpHostProblem)e;assertThat(p.code()).isEqualTo("HOST_MODEL_CHANGED");assertThat(p.report().generation().attempts()).isZero();});assertThat(second.calls).isOne();assertThat(changed.calls).isEqualTo(3);
 }
 @Test void finalLengthToolRequestsAndEmptyAnswersAreRejectedAfterDigestCheck(){
  for(var response:List.of(HostFixtures.response("partial","length",List.of()),HostFixtures.response("","stop",List.of()),HostFixtures.select())){var model=new HostFixtures.Model();model.script.add(response);var identity=new HostFixtures.Identity();assertThatThrownBy(()->HostFixtures.engine(new HostFixtures.Gateway(),new HostFixtures.Preparation(),model,identity,4096).workflow(HostFixtures.request())).isInstanceOf(McpHostProblem.class);assertThat(model.calls).isOne();assertThat(identity.calls).isEqualTo(2);}
 }
 @Test void composableGatewayPreservesPipelineAndAvailabilityHasZeroDownstreamCost(){
  var original=new HostFixtures.Gateway();var replacement=new HostFixtures.Gateway();replacement.failure=new GatewayException(GatewayException.Kind.AVAILABILITY,"MCP_UNAVAILABLE","Unavailable",new RemoteReadMetadata("taskshelf",RemoteReadMetadata.TOOL,"",1,0,0,1));var prep=new HostFixtures.Preparation();var model=new HostFixtures.Model();var identity=new HostFixtures.Identity();
  var engine=HostFixtures.engine(original,prep,model,identity,4096).withGateway(replacement);
  assertThatThrownBy(()->engine.workflow(HostFixtures.request())).isInstanceOf(McpHostProblem.class).satisfies(e->{var p=(McpHostProblem)e;assertThat(p.gatewayKind()).isEqualTo(GatewayException.Kind.AVAILABILITY);assertThat(p.report().gatewayAttempts()).isOne();assertThat(p.report().remote().initializations()).isOne();assertThat(p.report().queryEmbeddingAttempts()).isZero();assertThat(p.report().generation().attempts()).isZero();});assertThat(original.calls).isZero();assertThat(replacement.calls).isOne();assertThat(prep.calls).isZero();assertThat(model.calls).isZero();assertThat(identity.calls).isZero();
 }
 static void assertFinal(org.springframework.ai.chat.prompt.ChatOptions value){var options=(OllamaChatOptions)value;assertThat(options.getNumCtx()).isEqualTo(4096);assertThat(options.getNumPredict()).isEqualTo(256);assertThat(options.getTemperature()).isEqualTo(.2);assertThat(options.getToolCallbacks()).isNullOrEmpty();}
}
