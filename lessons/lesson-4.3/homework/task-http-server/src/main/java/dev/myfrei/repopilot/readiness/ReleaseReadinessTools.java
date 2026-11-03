package dev.myfrei.repopilot.readiness;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.http.ToolInputSchemas;
import dev.myfrei.repopilot.mcp.TaskServerJson;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("release-homework")
public class ReleaseReadinessTools implements ToolInputSchemas {
    private final TaskCatalog catalog;private final ReleaseReadinessPolicy policy;
    public ReleaseReadinessTools(TaskCatalog catalog,ReleaseReadinessPolicy policy){this.catalog=catalog;this.policy=policy;}
    @McpTool(name="getReleaseReadiness",description="Оценить server-owned COURSE_FIXTURE metadata проверки задачи. Не запускает реальные build/tests и не является production CI verdict.",
            annotations=@McpTool.McpAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false))
    public CallToolResult readiness(CallToolRequest request){
        var args=request==null?null:request.arguments();
        if(args==null||!args.keySet().equals(Set.of("taskId"))||!(args.get("taskId") instanceof String id)||!id.matches("TASK-[0-9]{3}"))
            return TaskServerJson.error("INVALID_ARGUMENTS","Ожидается только строковый taskId формата TASK-101.");
        var task=catalog.findTask(id);if(task.isEmpty())return TaskServerJson.error("TASK_NOT_FOUND","Задача не найдена.");
        // These observations are curated fixtures, not assertions that any real commands ran.
        var events=new ArrayList<ReadinessInput.Event>();
        for(String name:ReadinessInput.REQUIRED)events.add(new ReadinessInput.Event(name,2,1,id.equals("TASK-105")?"PASS":"UNKNOWN"));
        try{return TaskServerJson.result(policy.assess(new ReadinessInput(id,task.get().status().name(),2,events)));}
        catch(UnsupportedOperationException error){return TaskServerJson.error("HOMEWORK_NOT_IMPLEMENTED","Реализуйте ReleaseReadinessPolicy.assess.");}
        catch(RuntimeException error){return TaskServerJson.error("READINESS_FAILED","Не удалось оценить metadata готовности.");}
    }
    @Override public Map<String,Map<String,Object>> toolInputSchemas(){
        return Map.of("getReleaseReadiness",Map.of("type","object","properties",Map.of("taskId",Map.of("type","string","pattern","^TASK-[0-9]{3}$","maxLength",8)),"required",List.of("taskId"),"additionalProperties",false));
    }
}
