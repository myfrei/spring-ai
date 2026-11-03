package dev.myfrei.repopilot.agent;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
import com.jayway.jsonpath.JsonPath;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.tools.OllamaToolIdentity;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(25)
class AgentHttpIntegrationTest {
 static final JsonMapper JSON=JsonMapper.builder().build();static final ExecutorService EXECUTOR=Executors.newCachedThreadPool();
 static final AtomicReference<String> MODE=new AtomicReference<>("VALID"),DIGEST=new AtomicReference<>("a".repeat(64));static final AtomicInteger TAGS=new AtomicInteger();static final List<String> REQUESTS=new CopyOnWriteArrayList<>();static final HttpServer OLLAMA=server();
 @LocalServerPort int port;@Autowired AgentStore store;@Autowired ChatModel chatModel;@Autowired RestClient.Builder rest;
 @DynamicPropertySource static void configure(DynamicPropertyRegistry props){props.add("repopilot.agent.enabled",()->true);props.add("repopilot.rag.enabled",()->false);props.add("spring.ai.ollama.base-url",()->url());props.add("spring.ai.ollama.chat.model",()->"test-model");}
 @BeforeEach void reset(){MODE.set("VALID");DIGEST.set("a".repeat(64));TAGS.set(0);REQUESTS.clear();}
 @AfterAll static void stop(){OLLAMA.stop(0);EXECUTOR.shutdownNow();}
 @Test void actualOllamaRunsTwoManualToolStepsThenFinalAndPublishesCorrelatedRawJournal()throws Exception{
  UUID conversation=create();var response=run(conversation,"PRIVATE-QUESTION: изучиTASK-101 {literal}");assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("COMPLETED");assertThat(JsonPath.<String>read(response.body(),"$.stopReason")).isEqualTo("FINAL_ANSWER");assertThat(REQUESTS).hasSize(3);assertThat(TAGS).hasValue(6);
  assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isEqualTo(3);assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isEqualTo(2);assertThat(JsonPath.<Integer>read(response.body(),"$.report.executedTools")).isEqualTo(2);assertThat(JsonPath.<Integer>read(response.body(),"$.report.providerInputTokens")).isEqualTo(24);assertThat(JsonPath.<Integer>read(response.body(),"$.report.providerOutputTokens")).isEqualTo(9);
  for(String request:REQUESTS){assertThat(JsonPath.<Boolean>read(request,"$.stream")).isFalse();assertThat(JsonPath.<Integer>read(request,"$.options.num_ctx")).isEqualTo(4096);assertThat(JsonPath.<Integer>read(request,"$.options.num_predict")).isEqualTo(384);assertThat(JsonPath.<Double>read(request,"$.options.temperature")).isEqualTo(0.0);assertThat(JsonPath.<List<String>>read(request,"$.tools[*].function.name")).contains("getProjectInfo","listTasks","getTask");assertThat(request).doesNotContain("internalToolExecutionEnabled");}
  assertThat(JsonPath.<List<String>>read(REQUESTS.getLast(),"$.messages[*].role")).containsExactly("system","user","assistant","tool","assistant","tool");assertThat(REQUESTS.getLast()).contains("TASK-101").doesNotContain("acceptanceCriteria");
  UUID id=UUID.fromString(JsonPath.read(response.body(),"$.id"));var journal=store.messages(id);AgentEngineTest.assertBalanced(journal);assertThat(journal).hasSize(6);var raw=get("/api/agent/runs/"+id+"/messages");assertThat(raw.statusCode()).isEqualTo(200);assertThat(raw.body()).contains("PRIVATE-QUESTION","toolResults","TASK-101");var events=get("/api/agent/runs/"+id+"/events");assertThat(events.statusCode()).isEqualTo(200);assertThat(events.body()+JSON.writeValueAsString(JsonPath.read(response.body(),"$.report"))).doesNotContain("PRIVATE-QUESTION","summary","arguments\"","acceptanceCriteria");assertThat(get("/api/agent/runs/"+id).body()).contains("COMPLETED");assertThat(store.conversation(conversation).turns()).hasSize(1);
 }
 @Test void wrongClosedArgumentsStopAndExposeCorrelatedErrorWithoutClaimingCompletion()throws Exception{
  MODE.set("WRONG_ARGS");UUID conversation=create();var response=run(conversation,"Q");assertThat(response.statusCode()).isEqualTo(200);assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("STOPPED");assertThat(JsonPath.<String>read(response.body(),"$.stopReason")).isEqualTo("TOOL_ARGUMENTS");assertThat(JsonPath.<Integer>read(response.body(),"$.report.executedTools")).isZero();assertThat(REQUESTS).hasSize(1);assertThat(TAGS).hasValue(2);assertThat(store.conversation(conversation).turns()).isEmpty();var id=UUID.fromString(JsonPath.read(response.body(),"$.id"));AgentEngineTest.assertBalanced(store.messages(id));assertThat(store.messages(id).getLast().toolResults().getFirst().resultJson()).contains("TOOL_ARGUMENTS","\"ok\":false");
 }
 @Test void nativeProviderFailureHasOneAttemptUnknownUsageAndDigestAfterError()throws Exception{
  MODE.set("ERROR");var response=run(create(),"Q");assertThat(response.statusCode()).isEqualTo(200);assertThat(JsonPath.<String>read(response.body(),"$.stopReason")).isEqualTo("MODEL_UNAVAILABLE");assertThat(JsonPath.<Boolean>read(response.body(),"$.report.usageComplete")).isFalse();assertThat(REQUESTS).hasSize(1);assertThat(TAGS).hasValue(2);assertThat(response.body()).doesNotContain("PRIVATE-PROVIDER");
 }
 @Test void digestDriftOnFinalResponsePreventsCommitAfterValidToolSteps()throws Exception{
  MODE.set("FINAL_DRIFT");UUID conversation=create();var response=run(conversation,"Q");assertThat(JsonPath.<String>read(response.body(),"$.stopReason")).isEqualTo("MODEL_CHANGED");assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("STOPPED");assertThat(REQUESTS).hasSize(3);assertThat(TAGS).hasValue(6);assertThat(store.conversation(conversation).turns()).isEmpty();var id=UUID.fromString(JsonPath.read(response.body(),"$.id"));AgentEngineTest.assertBalanced(store.messages(id));assertThat(store.messages(id).getLast().role()).isEqualTo("TOOL");
 }
 @Test void modelAndToolCapsStopActualHttpAtDeclaredBounds()throws Exception{
  MODE.set("ENDLESS");var response=run(create(),"Q");assertThat(JsonPath.<String>read(response.body(),"$.stopReason")).isEqualTo("MODEL_LIMIT");assertThat(REQUESTS).hasSize(6);assertThat(TAGS).hasValue(12);assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isEqualTo(6);
  REQUESTS.clear();TAGS.set(0);MODE.set("BATCH9");var batch=run(create(),"Q");assertThat(JsonPath.<String>read(batch.body(),"$.stopReason")).isEqualTo("TOOL_LIMIT");assertThat(REQUESTS).hasSize(1);assertThat(JsonPath.<Integer>read(batch.body(),"$.report.executedTools")).isZero();
 }
 @Test void budgetRejectionHasNoActualOllamaHttpOrIdentityCalls(){
  var isolated=new AgentStore();var engine=new AgentEngine(chatModel,new OllamaToolIdentity(rest,url(),"test-model"),TaskAgentTools.create(new TaskCatalog()),isolated,"test-model",700);var run=engine.run(isolated.createConversation().id(),"Q");assertThat(run.stopReason()).isEqualTo("CONTEXT_LIMIT");assertThat(run.report().modelCalls()).isZero();assertThat(REQUESTS).isEmpty();assertThat(TAGS).hasValue(0);
 }
 @Test void failedPartialTurnDoesNotPolluteNextRunAndWholeSuccessfulHistoryIsIncluded()throws Exception{
  UUID conversation=create();MODE.set("WRONG_ARGS");run(conversation,"FAILED-QUESTION");MODE.set("VALID");REQUESTS.clear();var completed=run(conversation,"CLEAN-QUESTION");assertThat(JsonPath.<String>read(completed.body(),"$.status")).isEqualTo("COMPLETED");assertThat(REQUESTS.getFirst()).contains("CLEAN-QUESTION").doesNotContain("FAILED-QUESTION");REQUESTS.clear();var next=run(conversation,"NEXT-QUESTION");assertThat(JsonPath.<String>read(next.body(),"$.status")).isEqualTo("COMPLETED");assertThat(REQUESTS.getFirst()).contains("CLEAN-QUESTION","NEXT-QUESTION","TASK-101");assertThat(JsonPath.<Integer>read(next.body(),"$.report.includedHistoryTurns")).isOne();assertThat(store.conversation(conversation).turns()).hasSize(2);
 }
 @Test void validationAndUnknownIdsFailBeforeCreatingRunsOrCallingProvider()throws Exception{
  assertThat(post("/api/agent/conversations/"+UUID.randomUUID()+"/runs","{\"question\":\"Q\"}").statusCode()).isEqualTo(404);UUID conversation=create();assertThat(run(conversation,"").statusCode()).isEqualTo(400);assertThat(run(conversation,"x".repeat(1001)).statusCode()).isEqualTo(400);assertThat(get("/api/agent/runs/"+UUID.randomUUID()).statusCode()).isEqualTo(404);assertThat(get("/api/agent/runs/not-a-uuid").statusCode()).isEqualTo(400);assertThat(REQUESTS).isEmpty();assertThat(TAGS).hasValue(0);assertThat(store.conversation(conversation).activeRunId()).isNull();
 }
 private UUID create()throws Exception{var response=post("/api/agent/conversations","");assertThat(response.statusCode()).isEqualTo(200);return UUID.fromString(JsonPath.read(response.body(),"$.id"));}
 private HttpResponse<String>run(UUID id,String question)throws Exception{return post("/api/agent/conversations/"+id+"/runs",JSON.writeValueAsString(Map.of("question",question)));}
 private HttpResponse<String>post(String path,String body)throws Exception{return send(path,body);}private HttpResponse<String>get(String path)throws Exception{return send(path,null);}
 private HttpResponse<String>send(String path,String body)throws Exception{try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").method(body==null?"GET":"POST",body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}}
 private static String url(){return "http://127.0.0.1:"+OLLAMA.getAddress().getPort();}
 private static HttpServer server(){try{var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(EXECUTOR);server.createContext("/api/tags",x->{TAGS.incrementAndGet();respond(x,200,Map.of("models",List.of(Map.of("name","test-model","digest",DIGEST.get()))));});server.createContext("/api/chat",x->{
  String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);REQUESTS.add(body);String mode=MODE.get();if(mode.equals("ERROR")){respond(x,500,Map.of("error","PRIVATE-PROVIDER-BODY"));return;}
  List<Map<String,Object>>messages=JsonPath.read(body,"$.messages");int lastUser=0;for(int i=0;i<messages.size();i++)if(messages.get(i).get("role").equals("user"))lastUser=i;long results=messages.subList(lastUser,messages.size()).stream().filter(m->m.get("role").equals("tool")).count();var calls=new ArrayList<Map<String,Object>>();
  if(mode.equals("WRONG_ARGS"))calls.add(tool("getTask",Map.of("taskId","TASK-101","path","PRIVATE-PATH"),1));else if(mode.equals("ENDLESS"))calls.add(tool("getProjectInfo",Map.of(),REQUESTS.size()));else if(mode.equals("BATCH9")){for(int i=1;i<=9;i++)calls.add(tool("getProjectInfo",Map.of(),i));}else if(results==0)calls.add(tool("getProjectInfo",Map.of(),1));else if(results==1)calls.add(tool("getTask",Map.of("taskId","TASK-101"),2));
  var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",calls.isEmpty()?"Сведения TASK-101: порт8091.":"");if(!calls.isEmpty())message.put("tool_calls",calls);if(mode.equals("FINAL_DRIFT")&&calls.isEmpty())DIGEST.set("b".repeat(64));respond(x,200,Map.of("model","test-model","message",message,"done",true,"done_reason","stop","prompt_eval_count",8,"eval_count",3));});server.start();return server;
 }catch(IOException error){throw new UncheckedIOException(error);}}
 private static Map<String,Object>tool(String name,Map<String,Object>args,int id){return Map.of("id","call-"+id,"function",Map.of("name",name,"arguments",args));}
 private static void respond(HttpExchange x,int status,Object body)throws IOException{byte[]bytes=JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();}
}
