package dev.myfrei.repopilot.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TaskServerSdkIT {
    private static String jar() { return Path.of(System.getProperty("taskshelf.server.jar")).toAbsolutePath().toString(); }
    private static String java() { return Path.of(System.getProperty("java.home"),"bin","java").toString(); }
    private static JsonNode text(McpSchema.CallToolResult result) { return JsonMapper.builder().build().readTree(((McpSchema.TextContent)result.content().getFirst()).text()); }
    private static McpSchema.CallToolRequest call(String name,Map<String,Object> args) { return McpSchema.CallToolRequest.builder(name).arguments(args).build(); }

    @Test void actualSdkClientNegotiatesAndUsesAllThreePrimitives() throws Exception {
        try(var session=new Session()) {
            assertThat(session.initialized.protocolVersion()).isEqualTo("2025-11-25");
            var caps=session.initialized.capabilities();assertThat(caps.tools().listChanged()).isFalse();assertThat(caps.resources().subscribe()).isFalse();assertThat(caps.resources().listChanged()).isFalse();assertThat(caps.prompts().listChanged()).isFalse();assertThat(caps.logging()).isNotNull();
            assertThat(session.client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("listTasks","getTask");
            var list=session.client.callTool(call("listTasks",Map.of("limit",2)));assertThat(list.isError()).isFalse();assertThat(text(list).get("returned").asInt()).isEqualTo(2);
            var task=session.client.callTool(call("getTask",Map.of("taskId","TASK-101")));assertThat(text(task).get("title").asText()).contains("порт");assertThat(text(task).has("acceptanceCriteria")).isFalse();
            assertThat(session.client.listResources().resources()).extracting(McpSchema.Resource::uri).containsExactly(TaskMcpHandlers.OVERVIEW_URI);
            var resource=session.client.readResource(new McpSchema.ReadResourceRequest(TaskMcpHandlers.OVERVIEW_URI,null));assertThat(((McpSchema.TextResourceContents)resource.contents().getFirst()).text()).contains("TaskShelf");
            assertThat(session.client.listPrompts().prompts()).extracting(McpSchema.Prompt::name).containsExactly("analyze_task");
            var prompt=session.client.getPrompt(new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-101"),null));assertThat(prompt.messages()).hasSize(1);assertThat(prompt.messages().getFirst().role()).isEqualTo(McpSchema.Role.USER);
        }
    }

    @Test void knownToolErrorsRemainResultsButUnknownToolsUseJsonRpcError() throws Exception {
        try(var session=new Session()) {
            for(Object limit:List.of("2",2.5,2.0,0,6)) {var result=session.client.callTool(call("listTasks",Map.of("limit",limit)));assertThat(result.isError()).isTrue();assertThat(text(result).get("code").asText()).isEqualTo("INVALID_ARGUMENTS");}
            var missing=session.client.callTool(call("getTask",Map.of("taskId","TASK-999")));assertThat(missing.isError()).isTrue();assertThat(text(missing).get("code").asText()).isEqualTo("TASK_NOT_FOUND");
            assertThatThrownBy(()->session.client.callTool(call("missingTool",Map.of()))).isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32602);
            assertThat(session.client.callTool(call("getTask",Map.of("taskId","TASK-101"))).isError()).isFalse();
        }
    }

    @Test void invalidPromptsAndMissingResourcesAreRpcErrorsAndConnectionSurvives() throws Exception {
        try(var session=new Session()) {
            assertThatThrownBy(()->session.client.getPrompt(new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-999"),null)))
                    .isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32602);
            assertThatThrownBy(()->session.client.readResource(new McpSchema.ReadResourceRequest("taskshelf://missing",null)))
                    .isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32002);
            assertThat(session.client.listTools().tools()).hasSize(2);
        }
    }

    @Test void sdkClientCloseCleansOwnedSubprocessAndStderrContainsNoProtocolPayload() throws Exception {
        var session=new Session();var child=session.child;
        try {
            session.client.callTool(call("getTask",Map.of("taskId","RAW_PRIVATE_INPUT_DO_NOT_ECHO")));
        } finally {session.close();}
        assertThat(child.isAlive()).isFalse();assertThat(session.stderr).anyMatch(line->line.contains("taskshelf-mcp-server ready"));
        assertThat(String.join("\n",session.stderr)).doesNotContain("RAW_PRIVATE_INPUT_DO_NOT_ECHO","acceptanceCriteria","Согласовать HTTP-порт");
    }

    private static final class Session implements AutoCloseable {
        final List<String> stderr=new CopyOnWriteArrayList<>();
        final McpSyncClient client; final McpSchema.InitializeResult initialized; final ProcessHandle child;
        Session() {
            Set<Long> previous=new HashSet<>(ProcessHandle.current().children().map(ProcessHandle::pid).toList());
            var transport=new StdioClientTransport(ServerParameters.builder(java()).args("-jar",jar()).build(),TaskServerJson.mapper());transport.setStdErrorHandler(stderr::add);
            this.client=McpClient.sync(transport).clientInfo(McpSchema.Implementation.builder("course-sdk-test","1.0").build())
                    .requestTimeout(Duration.ofSeconds(5)).initializationTimeout(Duration.ofSeconds(5)).build();
            try {this.initialized=this.client.initialize();this.child=ProcessHandle.current().children().filter(item->!previous.contains(item.pid())).filter(item->Arrays.asList(item.info().arguments().orElse(new String[0])).contains(jar())).findFirst().orElseThrow();}
            catch(RuntimeException error) {this.client.closeGracefully();throw error;}
        }
        @Override public void close() throws Exception {
            this.client.closeGracefully();
            try {this.child.onExit().get(8,TimeUnit.SECONDS);} finally {if(this.child.isAlive())this.child.destroyForcibly();}
        }
    }
}
