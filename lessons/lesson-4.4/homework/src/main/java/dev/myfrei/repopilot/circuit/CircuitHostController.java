package dev.myfrei.repopilot.circuit;

import dev.myfrei.repopilot.mcphost.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mcp/resilient")
@ConditionalOnProperty(name={"repopilot.mcp-host.enabled","repopilot.rag.enabled"},havingValue="true")
public class CircuitHostController {
    private final McpHostEngine engine;
    public CircuitHostController(McpHostEngine baseline,RemoteTaskGateway gateway,CircuitGate gate){this.engine=baseline.withGateway(new CircuitTaskGateway(gateway,gate));}
    @PostMapping("/workflow") public McpHostReply workflow(@RequestBody McpHostRequest request){return engine.workflow(request);}
}
