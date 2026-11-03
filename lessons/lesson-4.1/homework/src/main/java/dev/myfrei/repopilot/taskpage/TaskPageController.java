package dev.myfrei.repopilot.taskpage;

import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tools/tasks")
@ConditionalOnProperty(name="repopilot.tools.enabled",havingValue="true")
public class TaskPageController {
    private final TaskPageTool tool;
    public TaskPageController(TaskCatalog catalog,TaskPagePolicy policy){this.tool=new TaskPageTool(catalog,policy);}
    @GetMapping("/definition") public ToolDefinition definition(){return tool.getToolDefinition();}
    @PostMapping(value="/page",produces=MediaType.APPLICATION_JSON_VALUE)
    public String page(@RequestBody String input){return tool.call(input);}
}
