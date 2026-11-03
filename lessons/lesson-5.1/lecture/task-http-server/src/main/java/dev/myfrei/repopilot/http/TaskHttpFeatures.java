package dev.myfrei.repopilot.http;

import java.util.*;
import dev.myfrei.repopilot.mcp.TaskMcpHandlers;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.springframework.ai.mcp.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class TaskHttpFeatures implements ToolInputSchemas {
    private final TaskMcpHandlers handlers;
    public TaskHttpFeatures(TaskMcpHandlers handlers) { this.handlers=handlers; }

    @McpTool(name="listTasks",description="Прочитать публичные задачи TaskShelf: limit integer1..5, default5.",
            annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false))
    public CallToolResult listTasks(CallToolRequest request) { return this.handlers.listTasks(request.arguments()); }

    @McpTool(name="getTask",description="Прочитать публичную TaskShelf задачу по точному taskId, например TASK-101.",
            annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false))
    public CallToolResult getTask(CallToolRequest request) { return this.handlers.getTask(request.arguments()); }

    @McpResource(uri=TaskMcpHandlers.OVERVIEW_URI,name="taskshelf-project-overview",title="TaskShelf: публичный обзор",mimeType="text/markdown",description="Описание вымышленного проекта и известные IDs задач.")
    public ReadResourceResult overview() { return this.handlers.overview().readHandler().apply(null,new ReadResourceRequest(TaskMcpHandlers.OVERVIEW_URI,null)); }

    @McpPrompt(name="analyze_task",description="Шаблон анализа публичной задачи без вызова модели.")
    public GetPromptResult analyzeTask(GetPromptRequest request,
            @McpArg(name="taskId",required=true,description="Точный TaskShelf ID, например TASK-101.") String taskId) {
        // McpArg supplies discovery metadata; validation uses the original raw arguments.
        return this.handlers.analyzeTask().promptHandler().apply(null,request);
    }

    @Override public Map<String,Map<String,Object>> toolInputSchemas() {
        var schemas=new LinkedHashMap<String,Map<String,Object>>();
        for(var spec:this.handlers.tools()) schemas.put(spec.tool().name(),spec.tool().inputSchema());
        return Map.copyOf(schemas);
    }
}
