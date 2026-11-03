package dev.myfrei.repopilot.tools;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tools")
@ConditionalOnProperty(name="repopilot.tools.enabled",havingValue="true")
public class ToolsController {
    private final TaskToolsService service;
    public ToolsController(TaskToolsService service) { this.service=service; }
    @PostMapping("/workflow") public TaskToolsService.WorkflowReply workflow(@RequestBody ToolRequest request) { return service.workflow(request); }
    @PostMapping("/model") public TaskToolsService.ModelReply model(@RequestBody ToolRequest request) { return service.model(request); }
}
