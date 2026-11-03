package dev.myfrei.repopilot.evidence;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class EvidenceAdapterTest{
 @TempDir Path directory;
 private final String path="src/Main.java";
 private String body(String hash){return AgentJson.encode(Map.of("claims",List.of(Map.of("path",path,"fullFileHash",hash,"startLine",1,"endLine",2))));}
 @Test void decoderAcceptsOnlyClaimsAndRejectsCallerSuppliedEvidenceAndCoercion(){
  String hash="a".repeat(64);var valid=body(hash);var request=EvidenceClaimsRequest.decode(valid);assertThat(request.claims()).hasSize(1);assertThatThrownBy(()->request.claims().clear()).isInstanceOf(UnsupportedOperationException.class);
  for(String invalid:List.of("{","{}","{\"claims\":[]}",valid.substring(0,valid.length()-1)+",\"reads\":[]}",valid.replace("\"startLine\":1","\"startLine\":1.5"),valid.replace("\"startLine\":1","\"startLine\":\"1\""),valid.replace("\"path\":\"src/Main.java\"","\"path\":null")))assertThatIllegalArgumentException().as(invalid).isThrownBy(()->EvidenceClaimsRequest.decode(invalid));
 }
 @Test void adapterReadsOnlyServerReceiptsAndHashesOnlyTheObservedVersions()throws Exception{
  Files.createDirectories(directory.resolve("src"));Files.writeString(directory.resolve(path),"one\ntwo\nthree\n");var workspace=new WorkspaceService(directory);var run=UUID.randomUUID();var observed=workspace.readFile(run.toString(),path,1,2);var capture=new CapturePolicy();var calls=new AtomicInteger();
  var checker=new EvidenceChecker(workspace::receipts,name->{calls.incrementAndGet();assertThat(name).isEqualTo(path);return workspace.currentHash(name);},capture);
  var actual=new EvidenceInput.Claim(path,observed.fullFileHash(),1,2);var unknown=new EvidenceInput.Claim("src/Unobserved.java",observed.fullFileHash(),1,2);
  checker.check(run,List.of(actual,unknown,actual));assertThat(calls).hasValue(1);assertThat(capture.input.reads()).hasSize(1);assertThat(capture.input.reads().getFirst().runId()).isEqualTo(run);assertThat(capture.input.currentHashes()).containsExactlyEntriesOf(Map.of(path,observed.fullFileHash()));
  String longPath="src/"+"x".repeat(206)+".java";Files.writeString(directory.resolve(longPath),"other\n");workspace.readFile(run.toString(),longPath,1,1);
  Files.writeString(directory.resolve(path),"changed\n");checker.check(run,List.of(actual));assertThat(calls).hasValue(2);assertThat(capture.input.currentHashes().get(path)).isNotEqualTo(observed.fullFileHash());assertThat(capture.input.reads()).hasSize(2);
  var unavailable=new EvidenceChecker(workspace::receipts,name->{throw new WorkspaceException("FILE_UNAVAILABLE");},capture);unavailable.check(run,List.of(actual));assertThat(capture.input.currentHashes()).isEmpty();
 }
 @Test void endpointUsesAFinishedRealRunAndActualReadReceiptAndReturnsTypedErrors()throws Exception{
  Files.createDirectories(directory.resolve("src"));Files.writeString(directory.resolve(path),"one\ntwo\nthree\n");var workspace=new WorkspaceService(directory);var store=new AgentStore();var capture=new CapturePolicy();var checker=new EvidenceChecker(workspace,capture);
  AgentTool reader=new AgentTool(){public String name(){return "readFile";}public String description(){return "Read fixture";}public String inputSchema(){return "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";}public AgentToolResult execute(String raw,AgentToolContext context){return AgentToolResult.success(AgentJson.encode(workspace.readFile(context.runId().toString(),path,1,2)));}};
  var engine=new AgentEngine(new Script(true),()->"c".repeat(64),List.of(reader),store,"test-model",4096);var run=engine.run(store.createConversation().id(),"Read fixture");assertThat(run.status()).isEqualTo("COMPLETED");var hash=workspace.receipts(run.id().toString()).getFirst().fullFileHash();
  var mvc=MockMvcBuilders.standaloneSetup(new EvidenceController(store,checker)).setControllerAdvice(new EvidenceExceptionHandler(new AgentExceptionHandler())).build();
  mvc.perform(post("/api/agent/runs/"+run.id()+"/evidence/check").contentType(MediaType.APPLICATION_JSON).content(body(hash))).andExpect(status().isOk()).andExpect(jsonPath("$.allSupported").value(true));assertThat(capture.input.runId()).isEqualTo(run.id());
  mvc.perform(post("/api/agent/runs/"+UUID.randomUUID()+"/evidence/check").contentType(MediaType.APPLICATION_JSON).content(body(hash))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
  mvc.perform(post("/api/agent/runs/"+run.id()+"/evidence/check").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVIDENCE_INVALID_REQUEST"));
  var noReads=new AgentEngine(new Script(false),()->"c".repeat(64),List.of(),store,"test-model",4096).run(store.createConversation().id(),"Hello");
  mvc.perform(post("/api/agent/runs/"+noReads.id()+"/evidence/check").contentType(MediaType.APPLICATION_JSON).content(body(hash))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECEIPTS_UNAVAILABLE"));
  var todoMvc=MockMvcBuilders.standaloneSetup(new EvidenceController(store,new EvidenceChecker(workspace,new EvidencePolicy(){@Override public EvidenceReport check(EvidenceInput ignored){throw new UnsupportedOperationException("fixture");}}))).setControllerAdvice(new EvidenceExceptionHandler(new AgentExceptionHandler())).build();
  todoMvc.perform(post("/api/agent/runs/"+run.id()+"/evidence/check").contentType(MediaType.APPLICATION_JSON).content(body(hash))).andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("EVIDENCE_NOT_IMPLEMENTED"));
 }
 private static class CapturePolicy extends EvidencePolicy{EvidenceInput input;@Override public EvidenceReport check(EvidenceInput input){this.input=input;return new EvidenceReport(input.runId(),input.claims().stream().map(c->new EvidenceReport.Check(c.path(),c.fullFileHash(),c.startLine(),c.endLine(),EvidenceReport.Status.SUPPORTED)).toList(),true);}}
 private static class Script implements ChatModel{private final boolean read;int calls;Script(boolean read){this.read=read;}public ChatResponse call(Prompt prompt){boolean tool=read&&calls++==0;var assistant=AssistantMessage.builder().content(tool?"":"Answer").toolCalls(tool?List.of(new AssistantMessage.ToolCall("read-1","function","readFile","{}")):List.of()).build();return new ChatResponse(List.of(new Generation(assistant,ChatGenerationMetadata.builder().finishReason("stop").build())));}}
}
