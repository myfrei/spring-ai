package dev.myfrei.repopilot.mcphost;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mcp")
@ConditionalOnProperty(name={"repopilot.mcp-host.enabled","repopilot.rag.enabled"},havingValue="true")
public class McpHostController {
    private final McpHostEngine engine;
    public McpHostController(McpHostEngine engine){this.engine=engine;}
    @PostMapping("/workflow")public McpHostReply workflow(@RequestBody McpHostRequest request){return engine.workflow(request);}
    @PostMapping("/model")public McpHostReply model(@RequestBody McpHostRequest request){return engine.model(request);}
}
