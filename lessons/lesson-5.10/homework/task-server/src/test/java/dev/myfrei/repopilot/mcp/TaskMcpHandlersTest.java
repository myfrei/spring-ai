package dev.myfrei.repopilot.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TaskMcpHandlersTest {
    private final TaskCatalog catalog = new TaskCatalog();
    private final TaskMcpHandlers handlers = new TaskMcpHandlers(this.catalog);
    private JsonNode text(McpSchema.CallToolResult result) { return JsonMapper.builder().build().readTree(((McpSchema.TextContent) result.content().getFirst()).text()); }
    private String content(McpSchema.CallToolResult result) { return ((McpSchema.TextContent)result.content().getFirst()).text(); }

    @Test void descriptorsAdvertiseStrictObjectSchemasAndReadOnlyHints() {
        var tools = this.handlers.tools();
        assertThat(tools).extracting(item -> item.tool().name()).containsExactly("listTasks", "getTask");
        for (var spec : tools) {
            assertThat(spec.tool().inputSchema()).containsEntry("type", "object").containsEntry("additionalProperties", false);
            assertThat(spec.tool().annotations().readOnlyHint()).isTrue(); assertThat(spec.tool().annotations().destructiveHint()).isFalse();
        }
        Map<?,?> properties=(Map<?,?>)tools.getFirst().tool().inputSchema().get("properties");
        assertThat(TaskServerJson.encode(properties.get("limit"))).contains("integer", "minimum", "maximum", "default");
        assertThat(tools.getLast().tool().inputSchema().get("required")).isEqualTo(List.of("taskId"));
    }

    @Test void listDefaultsToFiveAndReportsTruthfulBoundedPrefix() {
        var all = this.handlers.listTasks(null); var first = this.handlers.listTasks(Map.of("limit",2));
        assertThat(all.isError()).isFalse(); assertThat(text(all).get("tasks").size()).isEqualTo(5);
        assertThat(text(first).get("returned").asInt()).isEqualTo(2); assertThat(text(first).get("total").asInt()).isEqualTo(5);
        assertThat(text(first).get("tasks").get(0).get("id").asText()).isEqualTo("TASK-101");
        assertThat(text(first).get("tasks").get(1).get("id").asText()).isEqualTo("TASK-102");
        assertThat(JsonMapper.builder().build().readTree(TaskServerJson.encode(first.structuredContent()))).isEqualTo(text(first));
    }

    @Test void rawIntegerUnknownKeyAndNullLimitsReturnSanitizedToolErrors() {
        for (Object value : List.of(0,6,2.5,2.0,"2",true)) {
            var result = this.handlers.listTasks(Map.of("limit",value));
            assertThat(result.isError()).isTrue(); assertThat(text(result).get("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        }
        var nullValue = new HashMap<String,Object>(); nullValue.put("limit",null);
        assertThat(this.handlers.listTasks(nullValue).isError()).isTrue();
        var unknown = this.handlers.listTasks(Map.of("RAW_PRIVATE_INPUT_DO_NOT_ECHO",2));
        assertThat(unknown.isError()).isTrue(); assertThat(content(unknown)).doesNotContain("RAW_PRIVATE_INPUT_DO_NOT_ECHO");
    }

    @Test void getTaskSeparatesBadArgumentsFromUnknownWellFormedId() {
        assertThat(text(this.handlers.getTask(Map.of("taskId","TASK-101"))).get("id").asText()).isEqualTo("TASK-101");
        assertThat(text(this.handlers.getTask(Map.of("taskId","TASK-999"))).get("code").asText()).isEqualTo("TASK_NOT_FOUND");
        for (Map<String,Object> args : List.of(Map.<String,Object>of(),Map.<String,Object>of("taskId",101),Map.<String,Object>of("taskId","task-101"),Map.<String,Object>of("taskId","TASK-101","extra",true))) {
            var result=this.handlers.getTask(args);assertThat(result.isError()).isTrue();assertThat(text(result).get("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        }
        assertThat(this.handlers.getTask(null).isError()).isTrue();
    }

    @Test void allPublicSurfacesExcludeInternalCriteriaWhileCatalogRetainsThem() {
        var combined=new StringBuilder(content(this.handlers.listTasks(Map.of())));
        for(var task:this.catalog.listTaskViews()) combined.append(content(this.handlers.getTask(Map.of("taskId",task.id()))));
        combined.append(TaskServerJson.encode(this.handlers.overview().readHandler().apply(null,new McpSchema.ReadResourceRequest(TaskMcpHandlers.OVERVIEW_URI,null))));
        combined.append(TaskServerJson.encode(this.handlers.analyzeTask().promptHandler().apply(null,new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-101"),null))));
        assertThat(combined.toString()).doesNotContain("acceptanceCriteria");
        for(var task:this.catalog.listTaskViews()) for(String criterion:this.catalog.findTask(task.id()).orElseThrow().acceptanceCriteria()) assertThat(combined.toString()).doesNotContain(criterion);
        assertThat(this.catalog.findTask("TASK-101").orElseThrow().acceptanceCriteria()).hasSize(3);
    }

    @Test void resourceAndPromptReturnBoundedUtf8ContextWithoutModelExecution() {
        var resource=this.handlers.overview();assertThat(resource.resource().mimeType()).isEqualTo("text/markdown");
        var result=resource.readHandler().apply(null,new McpSchema.ReadResourceRequest(TaskMcpHandlers.OVERVIEW_URI,null));
        var contents=(McpSchema.TextResourceContents)result.contents().getFirst();
        assertThat(contents.text()).contains("TaskShelf","TASK-101","Java 25");assertThat(contents.text().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(TaskServerJson.MAX_OUTPUT_BYTES);
        var prompt=this.handlers.analyzeTask().promptHandler().apply(null,new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-101"),null));
        assertThat(prompt.messages()).hasSize(1);assertThat(prompt.messages().getFirst().role()).isEqualTo(McpSchema.Role.USER);
        assertThat(((McpSchema.TextContent)prompt.messages().getFirst().content()).text()).contains("TASK-101","не утверждай");
    }

    @Test void invalidPromptAndUnknownResourceAreProtocolErrors() {
        assertThatThrownBy(()->this.handlers.analyzeTask().promptHandler().apply(null,new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId","TASK-999"),null)))
                .isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32602);
        assertThatThrownBy(()->this.handlers.analyzeTask().promptHandler().apply(null,new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId",101),null))).isInstanceOf(McpError.class);
        assertThatThrownBy(()->this.handlers.overview().readHandler().apply(null,new McpSchema.ReadResourceRequest("taskshelf://missing",null)))
                .isInstanceOf(McpError.class).extracting(error->((McpError)error).getJsonRpcError().code()).isEqualTo(-32002);
    }

    @Test void outputBudgetCountsUtf8BytesAndDoesNotReturnOversizedPayload() {
        assertThat(TaskServerJson.boundedText("я".repeat(4096))).hasSize(4096);
        assertThatIllegalArgumentException().isThrownBy(()->TaskServerJson.boundedText("я".repeat(4097)));
        var result=TaskServerJson.result(Map.of("large","я".repeat(5000)));
        assertThat(result.isError()).isTrue();assertThat(text(result).get("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(content(result).getBytes(StandardCharsets.UTF_8).length).isLessThan(8192);
    }

    @Test void extraToolsCannotShadowBaselineNamesBeforeTransportStarts() {
        var transport=new StdioServerTransportProvider(TaskServerJson.mapper(),new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream());
        assertThatIllegalArgumentException().isThrownBy(()->TaskMcpServer.create(transport,this.catalog,List.of(this.handlers.tools().getFirst()))).withMessage("Duplicate tool name");
    }
}
