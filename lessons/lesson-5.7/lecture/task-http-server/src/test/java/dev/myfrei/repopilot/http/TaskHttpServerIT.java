package dev.myfrei.repopilot.http;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import dev.myfrei.repopilot.mcp.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"taskshelf.access-token=http-course-test-token"})
@ExtendWith(OutputCaptureExtension.class)
@Timeout(20)
class TaskHttpServerIT {
    private static final String TOKEN="http-course-test-token";
    private static final String VERSION="2025-11-25";
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String base(){return "http://127.0.0.1:"+this.port;}
    private HttpRequest.Builder request(){return HttpRequest.newBuilder(URI.create(base()+"/mcp")).timeout(Duration.ofSeconds(5)).header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",VERSION);}
    private HttpRequest.Builder authorized(){return request().header("Authorization","Bearer "+TOKEN);}
    private HttpResponse<String> send(HttpRequest.Builder request,String method,Object body)throws Exception{
        return this.http.send(request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(TaskServerJson.encode(body),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private Map<String,Object> rpc(int id,String method,Map<String,Object> params){return Map.of("jsonrpc","2.0","id",id,"method",method,"params",params);}
    private HttpResponse<String> initialize(String origin)throws Exception{
        var builder=authorized();if(origin!=null)builder.header("Origin",origin);
        return send(builder,"POST",rpc(1,"initialize",Map.of("protocolVersion",VERSION,"capabilities",Map.of(),"clientInfo",Map.of("name","http-wire-test","version","1.0"))));
    }
    private String session(HttpResponse<String> response){assertThat(response.statusCode()).isEqualTo(200);return response.headers().firstValue("mcp-session-id").orElseThrow();}
    private JsonNode jsonRpc(HttpResponse<String> response){
        String text=response.body();if(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))text=text.lines().filter(line->line.startsWith("data:")).map(line->line.substring(5).strip()).filter(line->!line.isEmpty()).findFirst().orElseThrow();
        return JsonMapper.builder().build().readTree(text);
    }
    private static McpSchema.CallToolRequest call(String name,Map<String,Object> args){return McpSchema.CallToolRequest.builder(name).arguments(args).build();}

    @Test void nativeSdkInitializesDiscoversCallsReadsAndGetsPromptWithBearerAndNoOrigin() {
        try(var session=new SdkSession()) {
            assertThat(session.initialized.protocolVersion()).isEqualTo(VERSION);var caps=session.initialized.capabilities();assertThat(caps.logging()).isNotNull();assertThat(caps.completions()).isNull();assertThat(caps.tools().listChanged()).isFalse();assertThat(caps.resources().subscribe()).isFalse();assertThat(caps.prompts().listChanged()).isFalse();
            assertThat(session.client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("listTasks","getTask");
            var task=session.client.callTool(call("getTask",Map.of("taskId","TASK-101")));assertThat(task.isError()).isFalse();assertThat(TaskServerJson.encode(task.structuredContent())).contains("TASK-101").doesNotContain("acceptanceCriteria");
            assertThat(session.client.listResources().resources()).extracting(McpSchema.Resource::uri).containsExactly(TaskMcpHandlers.OVERVIEW_URI);
            assertThat(((McpSchema.TextResourceContents)session.client.readResource(new McpSchema.ReadResourceRequest(TaskMcpHandlers.OVERVIEW_URI,null)).contents().getFirst()).text()).contains("TaskShelf");
            assertThat(session.client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).containsExactly("analyze_task");
            assertThat(session.client.getPrompt(new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-101"),null)).messages()).hasSize(1);
            assertThat(session.headers).allSatisfy(header->{assertThat(header.authorized()).isTrue();assertThat(header.origin()).isFalse();});
        }
    }
    @Test void allHttpMethodsRejectMissingOrWrongBearerBeforeCreatingSession()throws Exception {
        for(String method:List.of("GET","POST","DELETE","OPTIONS","HEAD","PATCH")) {
            var response=send(request(),method,null);assertThat(response.statusCode()).isEqualTo(401);assertThat(response.headers().firstValue("mcp-session-id")).isEmpty();assertThat(response.headers().firstValue("www-authenticate")).isPresent();
        }
        var response=send(request().header("Authorization","Bearer wrong-token"),"POST",rpc(1,"tools/list",Map.of()));assertThat(response.statusCode()).isEqualTo(401);assertThat(response.body()).contains("UNAUTHORIZED").doesNotContain(TOKEN,"wrong-token");
    }
    @Test void matrixAndEncodedPathsCannotBypassBearerOrOrigin()throws Exception {
        for(String path:List.of("/mcp;review=x","/%6dcp","/m%63p")) {
            var uri=URI.create(base()+path);
            var unauth=request().uri(uri);assertThat(send(unauth,"POST",rpc(1,"initialize",Map.of("protocolVersion",VERSION,"capabilities",Map.of(),"clientInfo",Map.of("name","path-test","version","1.0")))).statusCode()).isEqualTo(401);
            var foreign=authorized().uri(uri).header("Origin","http://evil.example");assertThat(send(foreign,"POST",null).statusCode()).isEqualTo(403);
        }
    }
    @Test void exactAllowedOriginsWorkAndOtherOriginsAre403()throws Exception {
        for(String origin:McpAccessFilter.ALLOWED_ORIGINS) {
            String id=session(initialize(origin));assertThat(send(authorized().header("Origin",origin).header("MCP-Session-Id",id),"DELETE",null).statusCode()).isEqualTo(200);
        }
        for(String origin:List.of("http://evil.example","null","http://localhost:8080/","http://localhost:8092"))assertThat(initialize(origin).statusCode()).isEqualTo(403);
        var response=send(request().header("Origin","http://evil.example"),"POST",null);assertThat(response.statusCode()).isEqualTo(403);
    }
    @Test void wireSessionHeaderNotificationPostSseDeleteAndUnknownSessionAreExplicit()throws Exception {
        var initialized=initialize(null);String id=session(initialized);assertThat(initialized.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");assertThat(jsonRpc(initialized).get("result").get("protocolVersion").asText()).isEqualTo(VERSION);
        var notification=send(authorized().header("MCP-Session-Id",id),"POST",Map.of("jsonrpc","2.0","method","notifications/initialized"));assertThat(notification.statusCode()).isEqualTo(202);assertThat(notification.body()).isEmpty();
        try {
            var list=send(authorized().header("MCP-Session-Id",id),"POST",rpc(2,"tools/list",Map.of()));assertThat(list.statusCode()).isEqualTo(200);assertThat(list.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");assertThat(jsonRpc(list).get("result").get("tools").size()).isEqualTo(2);
            var unknownMethod=send(authorized().header("MCP-Session-Id",id),"POST",rpc(9,"missing/method",Map.of()));assertThat(jsonRpc(unknownMethod).get("error").get("code").asInt()).isEqualTo(-32601);
            var nullLimitRequest=authorized().header("MCP-Session-Id",id).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"listTasks\",\"arguments\":{\"limit\":null}}}"));
            assertThat(jsonRpc(this.http.send(nullLimitRequest.build(),HttpResponse.BodyHandlers.ofString())).get("result").get("isError").asBoolean()).isTrue();
            var nullUnknownRequest=authorized().header("MCP-Session-Id",id).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"listTasks\",\"arguments\":{\"unknown\":null}}}"));
            assertThat(jsonRpc(this.http.send(nullUnknownRequest.build(),HttpResponse.BodyHandlers.ofString())).get("result").get("isError").asBoolean()).isTrue();
            assertThat(send(request().header("MCP-Session-Id",id),"DELETE",null).statusCode()).isEqualTo(401);
            assertThat(send(authorized(),"POST",rpc(3,"tools/list",Map.of())).statusCode()).isEqualTo(400);
            assertThat(send(authorized().header("MCP-Session-Id","missing-session"),"GET",null).statusCode()).isEqualTo(404);
        }finally{assertThat(send(authorized().header("MCP-Session-Id",id),"DELETE",null).statusCode()).isEqualTo(200);}
        assertThat(send(authorized().header("MCP-Session-Id",id),"DELETE",null).statusCode()).isEqualTo(404);
        assertThat(send(authorized().header("MCP-Session-Id",id),"POST",rpc(4,"tools/list",Map.of())).statusCode()).isEqualTo(404);
    }
    @Test void rawInvalidArgumentsAreToolErrorsWhileUnknownMethodsResourcesAndPromptsAreProtocolErrors()throws Exception {
        try(var session=new SdkSession()) {
            for(Object value:List.of("2",2.5,2.0,0,6)) {
                var result=session.client.callTool(call("listTasks",Map.of("limit",value)));assertThat(result.isError()).isTrue();assertThat(TaskServerJson.encode(result.structuredContent())).contains("INVALID_ARGUMENTS");
            }
            assertThat(session.client.callTool(call("getTask",Map.of("taskId","TASK-101","extra",true))).isError()).isTrue();
            var unknown=session.client.callTool(call("getTask",Map.of("taskId","TASK-999")));assertThat(unknown.isError()).isTrue();assertThat(TaskServerJson.encode(unknown.structuredContent())).contains("TASK_NOT_FOUND");
            assertThatThrownBy(()->session.client.callTool(call("missingTool",Map.of()))).isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32602);
            assertThatThrownBy(()->session.client.readResource(new McpSchema.ReadResourceRequest("taskshelf://missing",null))).isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32002);
            assertThatThrownBy(()->session.client.getPrompt(new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-999"),null))).isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32602);
            assertThat(session.client.listTools().tools()).hasSize(2);
        }
    }
    @Test void actualAnnotationSchemasAndResultsMatchSharedStdioContractAndRemainBounded() {
        var catalog=new TaskCatalog();var expected=new TaskMcpHandlers(catalog);var mapper=JsonMapper.builder().build();
        try(var session=new SdkSession()) {
            var schemas=new TaskHttpFeatures(expected).toolInputSchemas();for(var tool:session.client.listTools().tools())assertThat(tool.inputSchema()).isEqualTo(schemas.get(tool.name()));
            for(var task:catalog.listTaskViews()) {
                var args=Map.<String,Object>of("taskId",task.id());var result=session.client.callTool(call("getTask",args));
                assertThat(mapper.readTree(TaskServerJson.encode(result))).isEqualTo(mapper.readTree(TaskServerJson.encode(expected.getTask(args))));
                var text=((McpSchema.TextContent)result.content().getFirst()).text();assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);assertThat(text).doesNotContain("acceptanceCriteria");
            }
        }
    }
    @Test void sdkCloseSendsAuthorizedDeleteAndIndependentSessionsDoNotCloseEachOther()throws Exception {
        var first=new SdkSession();try(var second=new SdkSession()) {
            try {
                first.client.callTool(call("getTask",Map.of("taskId","TASK-101")));second.client.callTool(call("getTask",Map.of("taskId","TASK-102")));
                String firstId=first.sessionId(),secondId=second.sessionId();assertThat(firstId).isNotEqualTo(secondId);
                first.close();assertThat(first.headers).anyMatch(header->header.method().equals("DELETE")&&header.authorized()&&firstId.equals(header.session()));
                assertThat(send(authorized().header("MCP-Session-Id",firstId),"POST",rpc(2,"tools/list",Map.of())).statusCode()).isEqualTo(404);
                assertThat(second.client.callTool(call("getTask",Map.of("taskId","TASK-102"))).isError()).isFalse();
            }finally{first.close();}
        }
    }
    @Test void defaultLogsAndSanitizedToolFailuresContainNoBearerOrRawMarker(CapturedOutput output) {
        try(var session=new SdkSession()) {
            var result=session.client.callTool(call("getTask",Map.of("taskId","RAW_PRIVATE_INPUT_DO_NOT_LOG")));assertThat(result.isError()).isTrue();assertThat(TaskServerJson.encode(result)).doesNotContain("RAW_PRIVATE_INPUT_DO_NOT_LOG");
        }
        assertThat(output.getAll()).doesNotContain(TOKEN,"RAW_PRIVATE_INPUT_DO_NOT_LOG");
    }

    private record Headers(String method,boolean authorized,boolean origin,String session,String protocol) { }
    private final class SdkSession implements AutoCloseable {
        final List<Headers> headers=new CopyOnWriteArrayList<>();final McpSyncClient client;final McpSchema.InitializeResult initialized;boolean closed;
        SdkSession() {
            var transport=HttpClientStreamableHttpTransport.builder(base()).endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("Authorization","Bearer "+TOKEN))
                    .httpRequestCustomizer((builder,method,uri,body,context)->{
                        var values=builder.build().headers();this.headers.add(new Headers(method,values.firstValue("Authorization").orElse("").equals("Bearer "+TOKEN),values.firstValue("Origin").isPresent(),values.firstValue("MCP-Session-Id").orElse(""),values.firstValue("MCP-Protocol-Version").orElse("")));
                    }).build();
            this.client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).initializationTimeout(Duration.ofSeconds(5)).build();
            try{this.initialized=this.client.initialize();}catch(RuntimeException error){this.client.closeGracefully();throw error;}
        }
        String sessionId(){return this.headers.stream().map(Headers::session).filter(id->!id.isBlank()).findFirst().orElseThrow();}
        @Override public void close(){if(!this.closed){this.closed=true;this.client.closeGracefully();}}
    }
}
