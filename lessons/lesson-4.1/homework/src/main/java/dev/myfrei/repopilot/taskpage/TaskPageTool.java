package dev.myfrei.repopilot.taskpage;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Provided adapter. Intentionally not a Spring bean and not in the lecture model's tool allowlist. */
public class TaskPageTool implements ToolCallback {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final ToolDefinition DEFINITION=ToolDefinition.builder().name("listTasks")
            .description("Получить страницу публичных задач TaskShelf, отфильтрованную по status. Cursor действует только для того же снимка и фильтра.")
            .inputSchema("""
                {"type":"object","properties":{"status":{"type":"string","enum":["ALL","OPEN","IN_PROGRESS","DONE"]},"limit":{"type":"integer","minimum":1,"maximum":5},"cursor":{"type":"string","maxLength":200}},"required":["status","limit"],"additionalProperties":false}
                """).build();
    private final TaskCatalog catalog;
    private final TaskPagePolicy policy;
    public TaskPageTool(TaskCatalog catalog,TaskPagePolicy policy){this.catalog=catalog;this.policy=policy;}
    @Override public ToolDefinition getToolDefinition(){return DEFINITION;}
    @Override public String call(String input){
        Map<?,?> map;
        try {
            if(input==null||input.length()>1000)throw invalid();
            Object decoded=JSON.readValue(input,Object.class);
            if(!(decoded instanceof Map<?,?> object))throw invalid();map=object;
            if(!map.keySet().containsAll(Set.of("status","limit"))||!Set.of("status","limit","cursor").containsAll(map.keySet())
                    ||!(map.get("status") instanceof String status)||!Set.of("ALL","OPEN","IN_PROGRESS","DONE").contains(status)
                    ||!(map.get("limit") instanceof Integer limit)||limit<1||limit>5
                    ||(map.containsKey("cursor")&&(!(map.get("cursor") instanceof String cursor)||cursor.length()>200)))throw invalid();
        }catch(RuntimeException error){throw invalid();}
        var rows=catalog.listTaskViews().stream().map(task->new TaskPage.Row(task.id(),task.title(),task.status().name())).toList();
        var page=policy.page(rows,(String)map.get("status"),(Integer)map.get("limit"),(String)map.getOrDefault("cursor",null));
        return JSON.writeValueAsString(page);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid task page arguments");}
}
