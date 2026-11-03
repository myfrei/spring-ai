package dev.myfrei.repopilot.mcphost;
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
import dev.myfrei.repopilot.tools.OllamaToolIdentity;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(McpHostHttpIntegrationTest.HostTestConfiguration.class)
@Timeout(20)
class McpHostHttpIntegrationTest {
 static final JsonMapper JSON=JsonMapper.builder().build();static final ExecutorService EXECUTOR=Executors.newCachedThreadPool();
 static final McpWireFixture MCP=new McpWireFixture();static final AtomicReference<String> MODE=new AtomicReference<>("VALID"),DIGEST=new AtomicReference<>("a".repeat(64));
 static final List<String> REQUESTS=new CopyOnWriteArrayList<>();static final AtomicInteger TAGS=new AtomicInteger();static final HttpServer OLLAMA=server();
 static final HostFixtures.Preparation PREPARATION=new HostFixtures.Preparation();
 @LocalServerPort int port;
 @org.springframework.beans.factory.annotation.Autowired ChatClient.Builder chatBuilder;
 @org.springframework.beans.factory.annotation.Autowired RestClient.Builder restBuilder;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry props){props.add("spring.ai.ollama.base-url",()->"http://127.0.0.1:"+OLLAMA.getAddress().getPort());props.add("spring.ai.ollama.chat.model",()->"test-model");props.add("repopilot.rag.enabled",()->false);props.add("repopilot.mcp-host.enabled",()->false);}
 @TestConfiguration(proxyBeanMethods=false)static class HostTestConfiguration{
  @Bean(destroyMethod="close")LazyMcpTaskGateway testGateway(){return new LazyMcpTaskGateway(MCP.base(),McpWireFixture.TOKEN,Duration.ofSeconds(2));}
  @Bean McpHostEngine testEngine(LazyMcpTaskGateway gateway,ChatClient.Builder builder,RestClient.Builder rest){return new McpHostEngine(gateway,PREPARATION,builder,new OllamaToolIdentity(rest,"http://127.0.0.1:"+OLLAMA.getAddress().getPort(),"test-model"),"test-model",4096);}
  @Bean McpHostController testHostController(McpHostEngine engine){return new McpHostController(engine);}
 }
 @BeforeEach void reset(){MODE.set("VALID");DIGEST.set("a".repeat(64));REQUESTS.clear();TAGS.set(0);MCP.mode.set("VALID");PREPARATION.calls=0;PREPARATION.empty=false;}
 @AfterAll static void stop(){OLLAMA.stop(0);EXECUTOR.shutdownNow();MCP.close();}
 @Test void actualNativeMcpAndOllamaHttpUseOneSelectionAndOneToolFreeFinalWithIsolatedCaps()throws Exception{
  int before=MCP.calls.get();var response=call("model",request());assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(MCP.calls.get()-before).isOne();assertThat(PREPARATION.calls).isOne();assertThat(REQUESTS).hasSize(2);assertThat(TAGS).hasValue(4);
  assertThat(JsonPath.<Integer>read(response.body(),"$.report.selection.attempts")).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.selectionToolCalls")).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.generation.attempts")).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.queryEmbeddingAttempts")).isOne();
  assertThat(JsonPath.<Integer>read(response.body(),"$.report.selection.providerInputTokens")).isEqualTo(8);assertThat(JsonPath.<Integer>read(response.body(),"$.report.generation.providerInputTokens")).isEqualTo(8);
  String selection=REQUESTS.getFirst(),last=REQUESTS.getLast();assertThat(JsonPath.<List<String>>read(selection,"$.tools[*].function.name")).containsExactly(RemoteReadMetadata.TOOL);assertThat(JsonPath.<List<String>>read(selection,"$.tools[0].function.parameters.properties.taskId.enum")).containsExactly("TASK-101");assertThat(JsonPath.<Integer>read(selection,"$.options.num_predict")).isEqualTo(384);assertThat(JsonPath.<Double>read(selection,"$.options.temperature")).isEqualTo(0.0);
  assertThat(JsonPath.<Integer>read(last,"$.options.num_predict")).isEqualTo(256);assertThat(JsonPath.<Double>read(last,"$.options.temperature")).isEqualTo(.2);for(String body:REQUESTS)assertThat(JsonPath.<Integer>read(body,"$.options.num_ctx")).isEqualTo(4096);
  assertThat(JSON.readTree(last).path("tools").isMissingNode()||JSON.readTree(last).path("tools").isNull()||JSON.readTree(last).path("tools").isEmpty()).isTrue();assertThat(last).contains("PRIVATE-QUESTION","TASK-101","[S1]").doesNotContain("MCP_TASK_SELECTOR_V1");
  String report=JSON.writeValueAsString(JsonPath.read(response.body(),"$.report"));assertThat(report).doesNotContain("PRIVATE-QUESTION","summary","arguments","acceptanceCriteria");
 }
 @Test void noEvidenceWorkflowHasNoChatCallsAndNoModelDigestLookup()throws Exception{PREPARATION.empty=true;var response=call("workflow",request());assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("NO_EVIDENCE");assertThat(REQUESTS).isEmpty();assertThat(TAGS).hasValue(0);}
 @Test void providerFailureAndDigestDriftRemainErrorsWithOneAttemptAndNoRepair()throws Exception{
  MODE.set("ERROR");var response=call("model",request());assertThat(response.statusCode()).as(response.body()).isEqualTo(503);assertThat(response.body()).contains("HOST_MODEL_UNAVAILABLE").doesNotContain("PRIVATE-PROVIDER");assertThat(REQUESTS).hasSize(1);assertThat(TAGS).hasValue(2);assertThat(PREPARATION.calls).isZero();assertThat(JsonPath.<Boolean>read(response.body(),"$.report.selection.usageComplete")).isFalse();
  REQUESTS.clear();TAGS.set(0);MODE.set("DRIFT");var drift=call("model",request());assertThat(drift.statusCode()).isEqualTo(409);assertThat(drift.body()).contains("HOST_MODEL_CHANGED");assertThat(REQUESTS).hasSize(1);assertThat(TAGS).hasValue(2);
 }
 @Test void bareModelAndWrongTaskCannotReadRemoteAndMalformedRequestHasNoModelCost()throws Exception{
  for(String mode:List.of("BARE","WRONG")){MODE.set(mode);int before=MCP.calls.get();var response=call("model",request());assertThat(response.statusCode()).as(response.body()).isEqualTo(mode.equals("WRONG")?400:409);assertThat(MCP.calls.get()).isEqualTo(before);assertThat(PREPARATION.calls).isZero();}
  REQUESTS.clear();var invalid=call("model","{\"taskId\":\"TASK-101\",\"question\":\"\"}");assertThat(invalid.statusCode()).isEqualTo(400);assertThat(REQUESTS).isEmpty();
 }
 @Test void fullPromptBudgetRejectsBeforeActualProviderHttpAttempt(){
  var gateway=new HostFixtures.Gateway();var identity=new OllamaToolIdentity(restBuilder,"http://127.0.0.1:"+OLLAMA.getAddress().getPort(),"test-model");
  var engine=new McpHostEngine(gateway,PREPARATION,chatBuilder,identity,"test-model",700);
  assertThatThrownBy(()->engine.model(new McpHostRequest("TASK-101","PRIVATE-QUESTION"))).isInstanceOf(McpHostProblem.class).satisfies(e->{var p=(McpHostProblem)e;assertThat(p.code()).isEqualTo("HOST_CONTEXT_TOO_LARGE");assertThat(p.report().selection().attempts()).isZero();});assertThat(REQUESTS).isEmpty();assertThat(TAGS).hasValue(2);assertThat(gateway.calls).isZero();
 }
 @Test void remoteBusinessErrorUses502AndZeroRagOrFinalAttempts()throws Exception{MCP.mode.set("REMOTE_ERROR");var response=call("workflow",request());assertThat(response.statusCode()).as(response.body()).isEqualTo(502);assertThat(response.body()).contains("MCP_REMOTE_TOOL_ERROR","REMOTE_TOOL").doesNotContain("PRIVATE-REMOTE");assertThat(JsonPath.<Integer>read(response.body(),"$.report.queryEmbeddingAttempts")).isZero();assertThat(REQUESTS).isEmpty();}
 private HttpResponse<String> call(String route,String body)throws Exception{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/mcp/"+route)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
 private String request(){return JSON.writeValueAsString(Map.of("taskId","TASK-101","question","PRIVATE-QUESTION: порт {literal}?"));}
 private static HttpServer server(){try{var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(EXECUTOR);
  server.createContext("/api/tags",x->{TAGS.incrementAndGet();respond(x,200,Map.of("models",List.of(Map.of("name","test-model","digest",DIGEST.get()))));});
  server.createContext("/api/chat",x->{String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);REQUESTS.add(body);String mode=MODE.get();if(mode.equals("ERROR")){respond(x,500,Map.of("error","PRIVATE-PROVIDER-FAILURE"));return;}
   boolean selector=body.contains("MCP_TASK_SELECTOR_V1");var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",selector?"":"Порт 8091 [S1].");
   if(selector&&!mode.equals("BARE")){String id=mode.equals("WRONG")?"TASK-102":"TASK-101";message.put("tool_calls",List.of(Map.of("id","call-1","function",Map.of("name",RemoteReadMetadata.TOOL,"arguments",Map.of("taskId",id)))));}
   if(mode.equals("DRIFT"))DIGEST.set("b".repeat(64));respond(x,200,Map.of("model","test-model","message",message,"done",true,"done_reason","stop","prompt_eval_count",8,"eval_count",3));});server.start();return server;
 }catch(IOException e){throw new UncheckedIOException(e);}}
 private static void respond(HttpExchange x,int status,Object body)throws IOException{byte[]bytes=JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();}
}
