package dev.myfrei.repopilot.tools;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(30)
class ToolsHttpIntegrationTest {
    private static final ExecutorService EXECUTOR=Executors.newCachedThreadPool();
    private static final AtomicReference<String> MODE=new AtomicReference<>("VALID"),DIGEST=new AtomicReference<>("a".repeat(64));
    private static final AtomicInteger MODEL_CALLS=new AtomicInteger(),TAG_CALLS=new AtomicInteger();
    private static final List<String> REQUESTS=new CopyOnWriteArrayList<>();
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final HttpServer OLLAMA=server();
    @LocalServerPort int port;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("repopilot.tools.enabled",()->true);
        properties.add("repopilot.rag.enabled",()->false);
        properties.add("repopilot.rag.jdbc-url",()->"jdbc:postgresql://127.0.0.1:1/offline");
        properties.add("spring.ai.ollama.base-url",()->"http://127.0.0.1:"+OLLAMA.getAddress().getPort());
        properties.add("spring.ai.ollama.chat.model",()->"test-model");
    }
    @BeforeEach void reset() { MODE.set("VALID");DIGEST.set("a".repeat(64));MODEL_CALLS.set(0);TAG_CALLS.set(0);REQUESTS.clear(); }
    @AfterAll static void stop() { OLLAMA.stop(0);EXECUTOR.shutdownNow(); }
    @Test void deterministicWorkflowWorksWithoutLlmOrPostgresAndReturnsOnlyPublicTaskView() throws Exception {
        MODE.set("UNSUPPORTED");var response=call("/api/tools/workflow",request("TASK-101"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(),"$.task.id")).isEqualTo("TASK-101");
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isZero();
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isEqualTo(2);
        assertThat(response.body()).doesNotContain("acceptanceCriteria","PRIVATE-QUESTION");
        assertThat(MODEL_CALLS.get()).isZero();assertThat(TAG_CALLS.get()).isZero();
    }
    @Test void actualOllamaToolLoopExposesExactSchemasAndAccountsThreeRoundsWithoutDoubleUsage() throws Exception {
        var response=call("/api/tools/model",request("TASK-101"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(response.body(),"$.report.requiredTaskRead")).isTrue();
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.providerInputTokens")).isEqualTo(24);
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.providerOutputTokens")).isEqualTo(9);
        assertThat(MODEL_CALLS.get()).isEqualTo(3);assertThat(TAG_CALLS.get()).isEqualTo(2);
        String first=REQUESTS.getFirst();
        assertThat(JsonPath.<List<String>>read(first,"$.tools[*].function.name")).containsExactly("getProjectInfo","getTask");
        assertThat(JsonPath.<Boolean>read(first,"$.tools[1].function.parameters.additionalProperties")).isFalse();
        assertThat(JsonPath.<List<String>>read(first,"$.tools[1].function.parameters.required")).containsExactly("taskId");
        assertThat(JsonPath.<List<String>>read(first,"$.tools[1].function.parameters.properties.taskId.enum")).containsExactly("TASK-101");
        for (String payload:REQUESTS) {
            assertThat(JsonPath.<Double>read(payload,"$.options.temperature")).isEqualTo(0.0);
            assertThat(JsonPath.<Integer>read(payload,"$.options.num_predict")).isEqualTo(384);
            assertThat(JsonPath.<Integer>read(payload,"$.options.num_ctx")).isEqualTo(4096);
        }
        assertThat(JsonPath.<List<String>>read(REQUESTS.getLast(),"$.messages[*].role")).contains("tool");
        String report=JSON.writeValueAsString(JsonPath.read(response.body(),"$.report"));
        assertThat(report).doesNotContain("PRIVATE-QUESTION","responseData","arguments","acceptanceCriteria","summary");
        assertThat(JsonPath.<String>read(response.body(),"$.task.id")).isEqualTo("TASK-101");
    }
    @Test void providerUnsupportedModelIsExplicitFailureAndWorkflowRemainsAvailable() throws Exception {
        MODE.set("UNSUPPORTED");var response=call("/api/tools/model",request("TASK-101"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);assertThat(response.body()).contains("TOOLS_MODEL_UNSUPPORTED","/api/tools/workflow");
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isOne();
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isZero();
        assertThat(JsonPath.<Boolean>read(response.body(),"$.report.usageComplete")).isFalse();
        assertThat(MODEL_CALLS.get()).isOne();assertThat(TAG_CALLS.get()).isEqualTo(2);
        assertThat(call("/api/tools/workflow",request("TASK-101")).statusCode()).isEqualTo(200);assertThat(MODEL_CALLS.get()).isOne();
    }
    @Test void fabricatedFinalAnswerWithoutTaskToolCannotBecomeAFactualSuccess() throws Exception {
        MODE.set("BARE");var response=call("/api/tools/model",request("TASK-101"));
        assertThat(response.statusCode()).isEqualTo(409);assertThat(response.body()).contains("TOOLS_REQUIRED_NOT_CALLED").doesNotContain("FABRICATED-PRIVATE-ANSWER");
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isOne();assertThat(MODEL_CALLS.get()).isOne();
    }
    @Test void wrongTaskAndExtraArgumentsAreRejectedBeforeBusinessSuccessWithoutRepair() throws Exception {
        for (String mode:List.of("WRONG_TASK","EXTRA_ARGS")) {
            MODE.set(mode);int before=MODEL_CALLS.get();var response=call("/api/tools/model",request("TASK-101"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(),"$.report.successfulToolCalls")).isZero();
            assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isOne();
            assertThat(JsonPath.<String>read(response.body(),"$.report.invocations[0].outcome")).isEqualTo("REJECTED");
            assertThat(response.body()).doesNotContain("/etc/passwd");assertThat(MODEL_CALLS.get()-before).isOne();
        }
    }
    @Test void modelAndNativeToolLimitsStopActualHttpCallsAtDeclaredBounds() throws Exception {
        MODE.set("ENDLESS");var endless=call("/api/tools/model",request("TASK-101"));
        assertThat(endless.statusCode()).as(endless.body()).isEqualTo(409);assertThat(endless.body()).contains("TOOLS_MODEL_LIMIT");
        assertThat(MODEL_CALLS.get()).isEqualTo(4);assertThat(JsonPath.<Integer>read(endless.body(),"$.report.toolCalls")).isEqualTo(4);
        MODE.set("BATCH_LIMIT");int before=MODEL_CALLS.get();var batch=call("/api/tools/model",request("TASK-101"));
        assertThat(batch.statusCode()).as(batch.body()).isEqualTo(409);assertThat(batch.body()).contains("TOOLS_CALL_LIMIT");
        assertThat(MODEL_CALLS.get()-before).isOne();assertThat(JsonPath.<Integer>read(batch.body(),"$.report.toolCalls")).isEqualTo(4);
    }
    @Test void digestGuardAlsoRunsAfterRejectedToolCallAndAbortsOnChange() throws Exception {
        MODE.set("DRIFT");var response=call("/api/tools/model",request("TASK-101"));
        assertThat(response.statusCode()).isEqualTo(409);assertThat(response.body()).contains("TOOLS_MODEL_CHANGED");
        assertThat(MODEL_CALLS.get()).isOne();assertThat(TAG_CALLS.get()).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.successfulToolCalls")).isZero();
    }
    @Test void unavailableProviderHasOneTruthfulFailedAttemptAndUnknownUsage() throws Exception {
        MODE.set("ERROR");var response=call("/api/tools/model",request("TASK-101"));
        assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("TOOLS_MODEL_UNAVAILABLE").doesNotContain("PRIVATE-PROVIDER-BODY");
        assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isOne();
        assertThat(JsonPath.<Boolean>read(response.body(),"$.report.usageComplete")).isFalse();
        assertThat(MODEL_CALLS.get()).isOne();assertThat(TAG_CALLS.get()).isEqualTo(2);
    }
    @Test void unknownToolAndIncompleteFinalAnswerAreNeverSuccessful() throws Exception {
        MODE.set("UNKNOWN");var unknown=call("/api/tools/model",request("TASK-101"));
        assertThat(unknown.statusCode()).isEqualTo(409);assertThat(unknown.body()).contains("TOOLS_UNKNOWN_TOOL").doesNotContain("/secret-file");
        assertThat(JsonPath.<Integer>read(unknown.body(),"$.report.toolCalls")).isZero();
        MODE.set("INCOMPLETE");var incomplete=call("/api/tools/model",request("TASK-101"));
        assertThat(incomplete.statusCode()).as(incomplete.body()).isEqualTo(409);assertThat(incomplete.body()).contains("TOOLS_INCOMPLETE");
        assertThat(JsonPath.<Boolean>read(incomplete.body(),"$.report.requiredTaskRead")).isTrue();
    }
    @Test void validationRejectsUnknownIdsAndOversizedQuestionsBeforeAnyModelRequest() throws Exception {
        for (String body:List.of(request("TASK-999"),"{\"taskId\":\"TASK-101\",\"question\":\"\"}",
                JSON.writeValueAsString(Map.of("taskId","TASK-101","question","x".repeat(1001)))))
            assertThat(call("/api/tools/model",body).statusCode()).isEqualTo(400);
        assertThat(MODEL_CALLS.get()).isZero();assertThat(TAG_CALLS.get()).isZero();
    }
    @Test void concurrentRequestsKeepTheirToolAuditsAndTaskIdsSeparate() throws Exception {
        var first=CompletableFuture.supplyAsync(()->unchecked("TASK-101"));var second=CompletableFuture.supplyAsync(()->unchecked("TASK-102"));
        var responses=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        for (int i=0;i<responses.size();i++) {
            var response=responses.get(i);String id=i==0?"TASK-101":"TASK-102";
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(JsonPath.<String>read(response.body(),"$.task.id")).isEqualTo(id);
            assertThat(JsonPath.<Integer>read(response.body(),"$.report.modelCalls")).isEqualTo(3);assertThat(JsonPath.<Integer>read(response.body(),"$.report.toolCalls")).isEqualTo(2);
            assertThat(JsonPath.<String>read(response.body(),"$.report.invocations[1].taskId")).isEqualTo(id);
        }
        assertThat(MODEL_CALLS.get()).isEqualTo(6);
    }
    private HttpResponse<String> unchecked(String id) { try { return call("/api/tools/model",request(id)); } catch (Exception error) { throw new CompletionException(error); } }
    private static String request(String id) { return JSON.writeValueAsString(Map.of("taskId",id,"question","PRIVATE-QUESTION: опиши задачу {literal}")); }
    private HttpResponse<String> call(String path,String body) throws Exception {
        try (var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request,HttpResponse.BodyHandlers.ofString());
        }
    }
    private static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(EXECUTOR);
            server.createContext("/api/tags",exchange->{TAG_CALLS.incrementAndGet();respond(exchange,200,JSON.writeValueAsString(Map.of("models",List.of(Map.of("name","test-model","digest",DIGEST.get())))));});
            server.createContext("/api/chat",exchange->{
                int count=MODEL_CALLS.incrementAndGet();String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);REQUESTS.add(body);
                String mode=MODE.get();
                if (mode.equals("UNSUPPORTED")) { respond(exchange,400,"{\"error\":\"model test-model does not support tools\"}");return; }
                if (mode.equals("ERROR")) { respond(exchange,500,"{\"error\":\"PRIVATE-PROVIDER-BODY\"}");return; }
                List<Map<String,Object>> messages=JsonPath.read(body,"$.messages");
                String data=messages.stream().filter(message->message.get("role").equals("user")).map(message->(String)message.get("content")).findFirst().orElseThrow();
                String task=JsonPath.read(data,"$.taskId");long results=messages.stream().filter(message->message.get("role").equals("tool")).count();
                var calls=new ArrayList<Map<String,Object>>();String text="Получена задача "+task+".";String finish="stop";
                if (mode.equals("BARE")) text="FABRICATED-PRIVATE-ANSWER";
                else if (mode.equals("UNKNOWN")) calls.add(tool("readFile",Map.of("path","/secret-file"),count));
                else if (mode.equals("WRONG_TASK")||mode.equals("DRIFT")) { calls.add(tool("getTask",Map.of("taskId","TASK-102"),count));if (mode.equals("DRIFT")) DIGEST.set("b".repeat(64)); }
                else if (mode.equals("EXTRA_ARGS")) calls.add(tool("getTask",Map.of("taskId",task,"path","/etc/passwd"),count));
                else if (mode.equals("ENDLESS")) calls.add(tool("getProjectInfo",Map.of(),count));
                else if (mode.equals("BATCH_LIMIT")) for (int i=0;i<5;i++) calls.add(tool("getProjectInfo",Map.of(),count*10+i));
                else if (results==0) calls.add(tool("getProjectInfo",Map.of(),count));
                else if (results==1) calls.add(tool("getTask",Map.of("taskId",task),count));
                else if (mode.equals("INCOMPLETE")) finish="length";
                var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",calls.isEmpty()?text:"");if (!calls.isEmpty()) message.put("tool_calls",calls);
                respond(exchange,200,JSON.writeValueAsString(Map.of("model","test-model","message",message,"done",true,"done_reason",finish,"prompt_eval_count",8,"eval_count",3)));
            });
            server.start();return server;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static Map<String,Object> tool(String name,Map<String,Object> arguments,int id) { return Map.of("id","call-"+id,"function",Map.of("name",name,"arguments",arguments)); }
    private static void respond(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
}
