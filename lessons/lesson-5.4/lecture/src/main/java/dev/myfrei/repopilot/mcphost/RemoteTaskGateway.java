package dev.myfrei.repopilot.mcphost;

/** Composable remote boundary. Decorators must preserve validation and failure categories. */
@FunctionalInterface
public interface RemoteTaskGateway {
    ValidatedTaskRead readTask(String requestedId);
}
