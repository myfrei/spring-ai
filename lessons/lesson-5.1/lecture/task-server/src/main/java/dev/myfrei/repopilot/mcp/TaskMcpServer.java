package dev.myfrei.repopilot.mcp;

import java.time.Duration;
import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.*;

public final class TaskMcpServer {
    private TaskMcpServer() { }
    public static McpSyncServer create(McpServerTransportProvider transport, TaskCatalog catalog, List<SyncToolSpecification> extraTools) {
        var handlers = new TaskMcpHandlers(catalog); var tools = new ArrayList<>(handlers.tools());
        tools.addAll(List.copyOf(extraTools));
        var names = new HashSet<String>();
        for (var tool : tools) if (!names.add(tool.tool().name())) throw new IllegalArgumentException("Duplicate tool name");
        return McpServer.sync(transport).jsonMapper(TaskServerJson.mapper())
                .serverInfo("taskshelf-mcp-server", "1.0.0")
                .instructions("Read-only fictional TaskShelf catalog. Tools read public metadata, resources provide context, prompts only return templates.")
                .capabilities(McpSchema.ServerCapabilities.builder().logging().tools(false).resources(false, false).prompts(false).build())
                .requestTimeout(Duration.ofSeconds(5))
                // Handlers enforce strict raw integer/unknown-key checks and bounded, sanitized errors.
                .validateToolInputs(false)
                .tools(tools).resources(handlers.overview()).prompts(handlers.analyzeTask()).build();
    }
}
