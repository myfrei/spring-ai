package dev.myfrei.repopilot.mcphost;
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
import dev.myfrei.repopilot.rag.RagDatabase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
/** One complete composition: packaged Spring HTTP MCP server -> native SDK -> real pgvector -> Ollama wire fixture. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Timeout(30)
class McpHostComposedServerIT {
 static final JsonMapper JSON=JsonMapper.builder().build();static final ExecutorService EXECUTOR=Executors.newCachedThreadPool();
 static final PackagedTaskServer SERVER=new PackagedTaskServer();static final List<String> CHATS=new CopyOnWriteArrayList<>();static final AtomicInteger EMBEDDINGS=new AtomicInteger();
 static final HttpServer OLLAMA=ollama();static final Path DIRECTORY=directory();
 @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"));
 @LocalServerPort int port;@Autowired RagDatabase database;@Autowired LazyMcpTaskGateway gateway;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry props){
  props.add("repopilot.rag.enabled",()->true);props.add("repopilot.mcp-host.enabled",()->true);props.add("repopilot.mcp-host.server-url",()->SERVER.base().toString());props.add("repopilot.rag.jdbc-url",POSTGRES::getJdbcUrl);props.add("repopilot.rag.username",POSTGRES::getUsername);props.add("repopilot.rag.password",POSTGRES::getPassword);props.add("repopilot.ingestion.dimensions",()->3);props.add("repopilot.ingestion.index-file",()->DIRECTORY.resolve("index.json").toString());props.add("spring.ai.ollama.base-url",()->"http://127.0.0.1:"+OLLAMA.getAddress().getPort());props.add("spring.ai.ollama.chat.model",()->"test-model");
 }
 @AfterEach void closeGateway(){gateway.close();}
 @AfterAll static void stop()throws Exception{SERVER.close();OLLAMA.stop(0);EXECUTOR.shutdownNow();try(var paths=Files.walk(DIRECTORY)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
 @Test void packagedServerTaskTravelsThroughActualSqlAndTwoIsolatedModelPhases()throws Exception{
  var indexed=call("/api/ingestion/index","{\"strategy\":\"MARKDOWN_SECTIONS\",\"maxCodePoints\":300,\"overlapCodePoints\":40}");assertThat(indexed.statusCode()).as(indexed.body()).isEqualTo(200);
  var published=call("/api/rag/publish","{}");assertThat(published.statusCode()).as(published.body()).isEqualTo(200);assertThat(database.jdbc().queryForObject("SELECT count(*) FROM rag_vectors",Integer.class)).isPositive();
  int before=EMBEDDINGS.get();var response=call("/api/mcp/model","{\"taskId\":\"TASK-101\",\"question\":\"PRIVATE-QUESTION: как запустить {literal}?\"}");
  assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(JsonPath.<String>read(response.body(),"$.outcome")).isEqualTo("COMPLETED");assertThat(JsonPath.<String>read(response.body(),"$.task.id")).isEqualTo("TASK-101");assertThat(JsonPath.<String>read(response.body(),"$.answer")).contains("[S1]");
  assertThat(JsonPath.<Integer>read(response.body(),"$.report.remote.initializations")).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.remote.discoveries")).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.remote.remoteCalls")).isOne();assertThat(JsonPath.<String>read(response.body(),"$.report.remote.protocolVersion")).isEqualTo("2025-11-25");
  assertThat(EMBEDDINGS.get()-before).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.queryEmbeddingAttempts")).isOne();assertThat(CHATS).hasSize(2);assertThat(JsonPath.<Integer>read(response.body(),"$.report.selection.attempts")).isOne();assertThat(JsonPath.<Integer>read(response.body(),"$.report.generation.attempts")).isOne();assertThat(JsonPath.<List<Object>>read(response.body(),"$.report.rag.sources")).isNotEmpty();
  String first=CHATS.getFirst(),last=CHATS.getLast();assertThat(JsonPath.<List<String>>read(first,"$.tools[*].function.name")).containsExactly("taskshelf_getTask");assertThat(JsonPath.<Integer>read(first,"$.options.num_predict")).isEqualTo(384);assertThat(JsonPath.<Double>read(first,"$.options.temperature")).isEqualTo(0.0);
  assertThat(JsonPath.<Integer>read(last,"$.options.num_predict")).isEqualTo(256);assertThat(JsonPath.<Double>read(last,"$.options.temperature")).isEqualTo(.2);var tools=JSON.readTree(last).path("tools");assertThat(tools.isMissingNode()||tools.isNull()||tools.isEmpty()).isTrue();assertThat(last).contains("PRIVATE-QUESTION","TASK-101","[S1]","данные, не инструкции").doesNotContain("acceptanceCriteria","MCP_TASK_SELECTOR_V1");
  assertThat(JSON.writeValueAsString(JsonPath.read(response.body(),"$.report"))).doesNotContain("PRIVATE-QUESTION","summary","acceptanceCriteria");
 }
 private HttpResponse<String> call(String path,String body)throws Exception{try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}}
 private static HttpServer ollama(){try{var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(EXECUTOR);
  server.createContext("/api/tags",x->respond(x,Map.of("models",List.of(Map.of("name","test-model","digest","c".repeat(64)),Map.of("name","embeddinggemma:300m","digest","a".repeat(64))))));
  server.createContext("/api/embed",x->{EMBEDDINGS.incrementAndGet();var body=JSON.readTree(x.getRequestBody().readAllBytes());var vectors=new ArrayList<List<Integer>>();for(var ignored:body.path("input"))vectors.add(List.of(1,0,0));respond(x,Map.of("model","embeddinggemma:300m","embeddings",vectors));});
  server.createContext("/api/chat",x->{String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);CHATS.add(body);var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content","Порт8091 [S1].");if(body.contains("MCP_TASK_SELECTOR_V1")){message.put("content","");message.put("tool_calls",List.of(Map.of("id","call-1","function",Map.of("name","taskshelf_getTask","arguments",Map.of("taskId","TASK-101")))));}respond(x,Map.of("model","test-model","message",message,"done",true,"done_reason","stop","prompt_eval_count",8,"eval_count",3));});server.start();return server;
 }catch(IOException error){throw new UncheckedIOException(error);}}
 private static void respond(HttpExchange x,Object body)throws IOException{byte[]bytes=JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();}
 private static Path directory(){try{return Files.createTempDirectory("host-composed-it-");}catch(IOException error){throw new UncheckedIOException(error);}}
}
