package dev.myfrei.repopilot.tools;

import java.util.*;
import dev.myfrei.repopilot.tasks.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class TaskToolSet {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ToolRun run; private final TaskCatalog catalog; private final List<ToolCallback> callbacks;
    public TaskToolSet(TaskCatalog catalog,ToolRun run) {
        this.catalog=catalog;this.run=run;
        String projectSchema="{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
        String taskSchema=JSON.writeValueAsString(Map.of("type","object","properties",Map.of("taskId",Map.of("type","string","enum",List.of(run.taskId()))),
                "required",List.of("taskId"),"additionalProperties",false));
        ToolCallback project=FunctionToolCallback.builder("getProjectInfo",(Map<String,Object> args)->catalog.projectInfo())
                .description("Read TaskShelf project metadata. Does not read files or change state.").inputType(Map.class).inputSchema(projectSchema).build();
        ToolCallback task=FunctionToolCallback.builder("getTask",(Map<String,Object> args)->{
            var value=catalog.getTask((String)args.get("taskId"));run.observedTask(value);return value;
        }).description("Read the public view of the requested TaskShelf task. Does not expose internal acceptance criteria.")
                .inputType(Map.class).inputSchema(taskSchema).build();
        callbacks=List.of(guard(project,false),guard(task,true));
    }
    public List<ToolCallback> callbacks() { return callbacks; }
    public String invoke(String name,String arguments) {
        return callbacks.stream().filter(callback->callback.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow(()->run.problem("TOOLS_UNKNOWN_TOOL","Инструмент не входит в разрешённый набор.")).call(arguments);
    }
    private ToolCallback guard(ToolCallback delegate,boolean task) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            public String call(String raw) { return call(raw,null); }
            public String call(String raw,ToolContext context) {
                long start=System.nanoTime();int sequence=run.beginTool(getToolDefinition().name());String id=null;boolean validated=false;
                try {
                    if (raw==null||raw.isBlank()||raw.length()>2048) throw invalid();
                    Object parsed=JSON.readValue(raw,Object.class);
                    if (!(parsed instanceof Map<?,?> args)) throw invalid();
                    if (task) {
                        if (!args.keySet().equals(Set.of("taskId"))||!(args.get("taskId") instanceof String requested)) throw invalid();
                        if (catalog.findTask(requested).isEmpty()) throw run.problem("TOOLS_UNKNOWN_TASK","Tool argument taskId не входит в каталог.");
                        if (!run.taskId().equals(requested)) throw run.problem("TOOLS_WRONG_TASK","getTask разрешён только для taskId текущего запроса.");
                        id=requested;
                    } else if (!args.isEmpty()) throw invalid();
                    validated=true;
                    String result=delegate.call(raw,context);run.finishTool(sequence,id,"SUCCESS","",start);return result;
                } catch (RuntimeException error) {
                    String code=error instanceof ToolsProblem problem?problem.code():validated?"TOOLS_EXECUTION_FAILED":"TOOLS_ARGUMENTS_INVALID";
                    run.finishTool(sequence,id,validated?"FAILED":"REJECTED",code,start);
                    throw run.problem(code,"Вызов инструмента отклонён. Аргументы и результат в отчёт не записаны.");
                }
            }
            private ToolsProblem invalid() { return run.problem("TOOLS_ARGUMENTS_INVALID","Tool arguments не соответствуют точной схеме."); }
        };
    }
}
