package dev.myfrei.repopilot.mcp;

import java.util.List;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

public final class TaskServerMain {
    private TaskServerMain() { }
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("This server accepts no command-line arguments");
        start(new TaskCatalog(), List.of());
    }
    /** Optional extra tools support composition without duplicating STDIO bootstrap. */
    public static McpSyncServer start(TaskCatalog catalog, List<SyncToolSpecification> extraTools) {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        var transport = new StdioServerTransportProvider(TaskServerJson.mapper(), System.in, System.out, 8192);
        var server = TaskMcpServer.create(transport, catalog, extraTools);
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "taskshelf-mcp-shutdown"));
        System.err.println("taskshelf-mcp-server ready: STDIO, read-only catalog");
        // SDK transport owns non-daemon reader/writer threads and closes them when stdin reaches EOF.
        return server;
    }
}
