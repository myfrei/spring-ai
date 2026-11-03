package dev.myfrei.repopilot.agent;
public interface AgentTool {
 String name();
 String description();
 String inputSchema();
 AgentToolResult execute(String rawArguments,AgentToolContext context);
}
