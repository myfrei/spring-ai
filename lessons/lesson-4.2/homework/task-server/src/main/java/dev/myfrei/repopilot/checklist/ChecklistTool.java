package dev.myfrei.repopilot.checklist;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.mcp.TaskServerJson;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

/** Provided MCP adapter. The student implements only the policy, never the protocol bootstrap. */
public class ChecklistTool {
    private final TaskCatalog catalog;
    private final AcceptanceChecklistPolicy policy;
    public ChecklistTool(TaskCatalog catalog,AcceptanceChecklistPolicy policy){this.catalog=catalog;this.policy=policy;}
    public SyncToolSpecification specification(){
        var fields=Map.of(
                "taskId",Map.of("type","string","pattern","^TASK-[0-9]{3}$","maxLength",8),
                "maxItems",Map.of("type","integer","minimum",1,"maximum",8,"default",4),
                "maxCodePoints",Map.of("type","integer","minimum",40,"maximum",800,"default",400));
        var schema=Map.<String,Object>of("type","object","properties",fields,"required",List.of("taskId"),"additionalProperties",false);
        var hints=McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build();
        var definition=McpSchema.Tool.builder("getAcceptanceChecklist",schema)
                .description("Прочитать целые критерии приёмки TaskShelf. Неполный ответ явно помечен complete=false; недостающие критерии нельзя додумывать.")
                .annotations(hints).build();
        return new SyncToolSpecification(definition,(exchange,request)->call(request.arguments()));
    }
    public McpSchema.CallToolResult call(Map<String,Object> args){
        if(args==null||!args.containsKey("taskId")||!Set.of("taskId","maxItems","maxCodePoints").containsAll(args.keySet())
                ||!(args.get("taskId") instanceof String id)||!id.matches("TASK-[0-9]{3}"))return invalid();
        int items=4,budget=400;
        if(args.containsKey("maxItems")){if(!(args.get("maxItems") instanceof Integer value)||value<1||value>8)return invalid();items=value;}
        if(args.containsKey("maxCodePoints")){if(!(args.get("maxCodePoints") instanceof Integer value)||value<40||value>800)return invalid();budget=value;}
        var task=catalog.findTask(id);
        if(task.isEmpty())return TaskServerJson.error("TASK_NOT_FOUND","Задача с таким taskId не найдена.");
        try {return TaskServerJson.result(policy.build(new ChecklistInput(id,task.get().acceptanceCriteria(),items,budget)));}
        catch(UnsupportedOperationException error){return TaskServerJson.error("HOMEWORK_NOT_IMPLEMENTED","Заполните AcceptanceChecklistPolicy.build в каркасе домашнего задания.");}
        catch(RuntimeException error){return TaskServerJson.error("CHECKLIST_FAILED","Не удалось подготовить ограниченный checklist.");}
    }
    private static McpSchema.CallToolResult invalid(){return TaskServerJson.error("INVALID_ARGUMENTS","Проверьте taskId, maxItems 1–8 и maxCodePoints 40–800.");}
}
