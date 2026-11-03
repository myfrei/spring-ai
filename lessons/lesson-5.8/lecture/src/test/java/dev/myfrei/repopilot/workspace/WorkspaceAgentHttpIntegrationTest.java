package dev.myfrei.repopilot.workspace;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
import com.jayway.jsonpath.JsonPath;
import dev.myfrei.repopilot.agent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real Boot HTTP -> OllamaChatModel HTTP fixture -> Java tools -> real temporary Git repo. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(30)
class WorkspaceAgentHttpIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final ExecutorService EXECUTOR=Executors.newCachedThreadPool();
    static final AtomicReference<String> MODE=new AtomicReference<>("FULL");
    static final AtomicInteger TAGS=new AtomicInteger();
    static final List<String> REQUESTS=new CopyOnWriteArrayList<>();
    static final Path ROOT=temporary();
    static final HttpServer OLLAMA=server();
    static final String SOURCE="src/main/java/dev/myfrei/fixture/StockLedger.java",TEST="src/test/java/dev/myfrei/fixture/StockLedgerTest.java";
    @LocalServerPort int port;
    @Autowired WorkspaceService workspace;
    @Autowired AgentStore store;
    @Autowired List<AgentTool> tools;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props){
        props.add("repopilot.agent.enabled",()->true);props.add("repopilot.workspace.enabled",()->true);props.add("repopilot.workspace.root",()->ROOT.toString());
        props.add("repopilot.rag.enabled",()->false);props.add("spring.ai.ollama.base-url",()->"http://127.0.0.1:"+OLLAMA.getAddress().getPort());props.add("spring.ai.ollama.chat.model",()->"test-model");props.add("repopilot.context.window-tokens",()->16384);
    }
    @BeforeEach void reset()throws Exception{
        clear(ROOT);Path fixture=Path.of("workspace-fixture");
        for(String path:List.of("README.md","pom.xml",".gitignore",SOURCE,TEST,"src/main/java/dev/myfrei/fixture/StockRoomApp.java")){
            Path target=ROOT.resolve(path);Files.createDirectories(target.getParent());Files.copy(fixture.resolve(path),target);
        }
        git("init","--quiet");git("add","--","README.md","pom.xml",".gitignore","src/");git("commit","--quiet","-m","Fixture baseline");Files.writeString(ROOT.resolve(SOURCE),"\n// READ_ONLY_DIRTY_FIXTURE\n",StandardOpenOption.APPEND);
        MODE.set("FULL");REQUESTS.clear();TAGS.set(0);
    }
    @AfterAll static void stop()throws Exception{OLLAMA.stop(0);EXECUTOR.shutdownNow();clear(ROOT);Files.deleteIfExists(ROOT);}
    @Test void actualManualAgentUsesAllFiveToolsAndCitesOnlyDeliveredSourceAndTestRanges()throws Exception{
        UUID conversation=create();var response=run(conversation);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("COMPLETED");assertThat(JsonPath.<String>read(response.body(),"$.stopReason")).isEqualTo("FINAL_ANSWER");
        UUID id=UUID.fromString(JsonPath.read(response.body(),"$.id"));assertThat(REQUESTS).hasSize(4);assertThat(TAGS).hasValue(8);assertThat(JsonPath.<Integer>read(response.body(),"$.report.executedTools")).isEqualTo(6);assertThat(JsonPath.<Integer>read(response.body(),"$.report.successfulTools")).isEqualTo(6);
        assertThat(tools).extracting(AgentTool::name).containsExactlyInAnyOrder("getProjectInfo","listTasks","getTask","listFiles","searchText","readFile","gitStatus","gitDiff");
        assertThat(JsonPath.<List<String>>read(REQUESTS.getFirst(),"$.tools[*].function.name")).containsExactlyInAnyOrderElementsOf(tools.stream().map(AgentTool::name).toList());
        var receipts=workspace.receipts(id.toString());assertThat(receipts).hasSize(2);assertThat(receipts).extracting(ReadReceipt::path).containsExactly(SOURCE,TEST);
        assertThat(receipts.getFirst().startLine()).isEqualTo(17);assertThat(receipts.getFirst().endLine()).isEqualTo(25);assertThat(receipts.getFirst().fullFileHash()).isEqualTo(workspace.currentHash(SOURCE));
        assertThat(JsonPath.<String>read(response.body(),"$.answer")).contains(SOURCE+":17-25",receipts.getFirst().fullFileHash(),TEST+":12-15");
        assertThat(REQUESTS.getLast()).contains("READ_ONLY_DIRTY_FIXTURE","quantity>available","insufficientStockDoesNotChangeInventory");
        var journal=store.messages(id);var calls=journal.stream().flatMap(m->m.toolCalls().stream()).map(c->c.id()).toList();var replies=journal.stream().flatMap(m->m.toolResults().stream()).map(r->r.id()).toList();assertThat(replies).containsExactlyElementsOf(calls);
        assertThat(store.conversation(conversation).turns()).hasSize(1);assertThat(JSON.writeValueAsString(store.events(id))+JSON.writeValueAsString(JsonPath.read(response.body(),"$.report"))).doesNotContain("quantity>available","PRIVATE-QUESTION",ROOT.toString(),"READ_ONLY_DIRTY_FIXTURE");
        assertThat(Files.readString(ROOT.resolve(SOURCE))).endsWith("// READ_ONLY_DIRTY_FIXTURE\n");
    }
    @Test void deniedReadIsCorrelatedButNeverCreatesServerOwnedEvidence()throws Exception{
        MODE.set("DENIED");var response=run(create());UUID id=UUID.fromString(JsonPath.read(response.body(),"$.id"));assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("COMPLETED");assertThat(JsonPath.<Integer>read(response.body(),"$.report.successfulTools")).isZero();
        assertThat(REQUESTS).hasSize(2);assertThat(REQUESTS.getLast()).contains("PATH_NOT_ALLOWED");assertThat(store.messages(id).stream().flatMap(m->m.toolResults().stream()).findFirst().orElseThrow().resultJson()).doesNotContain("PRIVATE-OUTSIDE-CONTENT");assertThatThrownBy(()->workspace.receipts(id.toString())).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("RECEIPTS_UNAVAILABLE"));
    }
    @Test void missingGitIsExplainedWithoutAutomaticInitialization()throws Exception{
        clear(ROOT.resolve(".git"));Files.delete(ROOT.resolve(".git"));MODE.set("NO_GIT");var response=run(create());assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("COMPLETED");assertThat(REQUESTS.getLast()).contains("NO_GIT_REPOSITORY");assertThat(Files.exists(ROOT.resolve(".git"))).isFalse();
    }
    @Test void budgetedReadReceiptMatchesExactEnvelopeReceivedByProvider()throws Exception{
        Files.writeString(ROOT.resolve("src/Budget.java"),("😀".repeat(600)+"\n").repeat(3));MODE.set("BUDGET");var response=run(create());assertThat(JsonPath.<String>read(response.body(),"$.status")).isEqualTo("COMPLETED");UUID id=UUID.fromString(JsonPath.read(response.body(),"$.id"));
        var receipt=workspace.receipts(id.toString()).getFirst();assertThat(receipt.endLine()).isEqualTo(1);
        List<Map<String,Object>> messages=JsonPath.read(REQUESTS.getLast(),"$.messages");String envelope=(String)messages.stream().filter(m->m.get("role").equals("tool")).findFirst().orElseThrow().get("content");assertThat(envelope.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);assertThat(JsonPath.<Integer>read(envelope,"$.data.endLine")).isEqualTo(1);assertThat(JsonPath.<Boolean>read(envelope,"$.data.rangeComplete")).isFalse();assertThat(envelope).doesNotContain("TOOL_RESULT_INVALID");
    }
    @Test void repeatedConversationRunsHaveSeparateReceiptVersions()throws Exception{
        Files.writeString(ROOT.resolve("src/Budget.java"),"first version\n");MODE.set("BUDGET");UUID conversation=create();var first=run(conversation);String firstId=JsonPath.read(first.body(),"$.id");String oldHash=workspace.receipts(firstId).getFirst().fullFileHash();
        Files.writeString(ROOT.resolve("src/Budget.java"),"second version\n");var second=run(conversation);String secondId=JsonPath.read(second.body(),"$.id");assertThat(secondId).isNotEqualTo(firstId);assertThat(workspace.receipts(firstId).getFirst().fullFileHash()).isEqualTo(oldHash);assertThat(workspace.receipts(secondId).getFirst().fullFileHash()).isNotEqualTo(oldHash);assertThat(store.conversation(conversation).turns()).hasSize(2);
    }
    private UUID create()throws Exception{return UUID.fromString(JsonPath.read(post("/api/agent/conversations","").body(),"$.id"));}
    private HttpResponse<String> run(UUID id)throws Exception{return post("/api/agent/conversations/"+id+"/runs",JSON.writeValueAsString(Map.of("question","PRIVATE-QUESTION: где проверяется остаток? Приведи paths, строки и полный hash.")));}
    private HttpResponse<String> post(String path,String body)throws Exception{try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}}
    private static void git(String... args)throws Exception{var command=new ArrayList<>(List.of("git","-C",ROOT.toString(),"-c","user.name=Course Fixture","-c","user.email=course@example.invalid"));command.addAll(List.of(args));var process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);assertThat(process.waitFor(5,TimeUnit.SECONDS)).as(output).isTrue();assertThat(process.exitValue()).as(output).isZero();}
    private static Path temporary(){try{return Files.createTempDirectory("workspace-agent-http-");}catch(IOException e){throw new UncheckedIOException(e);}}
    private static void clear(Path path)throws IOException{if(!Files.exists(path))return;try(var files=Files.walk(path)){for(Path file:files.sorted(Comparator.reverseOrder()).filter(p->!p.equals(path)).toList())Files.delete(file);}}
    private static HttpServer server(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(EXECUTOR);
        server.createContext("/api/tags",x->{TAGS.incrementAndGet();respond(x,Map.of("models",List.of(Map.of("name","test-model","digest","a".repeat(64)))));});
        server.createContext("/api/chat",x->{
            String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);REQUESTS.add(body);List<Map<String,Object>> messages=JsonPath.read(body,"$.messages");int lastUser=0;for(int i=0;i<messages.size();i++)if(messages.get(i).get("role").equals("user"))lastUser=i;
            var current=messages.subList(lastUser,messages.size());var replies=current.stream().filter(m->m.get("role").equals("tool")).toList();int count=replies.size();var calls=new ArrayList<Map<String,Object>>();
            switch(MODE.get()){
                case "FULL"->{if(count==0){calls.add(tool("listFiles",Map.of("directory",".","maxEntries",20),1));calls.add(tool("searchText",Map.of("query","quantity>available","directory","src","maxResults",5),2));}
                    else if(count==2){calls.add(tool("readFile",Map.of("path",SOURCE,"startLine",17,"endLine",25),3));calls.add(tool("readFile",Map.of("path",TEST,"startLine",12,"endLine",15),4));}
                    else if(count==4){calls.add(tool("gitStatus",Map.of(),5));calls.add(tool("gitDiff",Map.of("path",SOURCE,"scope","WORKTREE"),6));}}
                case "DENIED"->{if(count==0)calls.add(tool("readFile",Map.of("path","../PRIVATE-OUTSIDE-CONTENT","startLine",1,"endLine",1),1));}
                case "NO_GIT"->{if(count==0)calls.add(tool("gitStatus",Map.of(),1));}
                case "BUDGET"->{if(count==0)calls.add(tool("readFile",Map.of("path","src/Budget.java","startLine",1,"endLine",3),1));}
            }
            var answer=new StringBuilder("Проверены только данные инструментов. ");
            if(calls.isEmpty())for(var reply:replies){var value=JSON.readTree((String)reply.get("content"));var data=value.get("data");if(value.get("ok").asBoolean()&&data.has("fullFileHash")&&data.has("startLine"))answer.append(data.get("path").asText()).append(":").append(data.get("startLine").asInt()).append("-").append(data.get("endLine").asInt()).append(" sha256=").append(data.get("fullFileHash").asText()).append("; ");else if(!value.get("ok").asBoolean())answer.append(value.get("code").asText());}
            var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",calls.isEmpty()?answer.toString():"");if(!calls.isEmpty())message.put("tool_calls",calls);
            respond(x,Map.of("model","test-model","message",message,"done",true,"done_reason","stop","prompt_eval_count",8,"eval_count",3));
        });server.start();return server;
    }catch(IOException e){throw new UncheckedIOException(e);}}
    private static Map<String,Object> tool(String name,Map<String,Object> args,int id){return Map.of("id","workspace-call-"+id,"function",Map.of("name",name,"arguments",args));}
    private static void respond(HttpExchange exchange,Object body)throws IOException{byte[] bytes=JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}
