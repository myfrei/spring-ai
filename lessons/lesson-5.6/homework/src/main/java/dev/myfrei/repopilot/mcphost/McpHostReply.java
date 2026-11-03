package dev.myfrei.repopilot.mcphost;

import dev.myfrei.repopilot.tasks.TaskView;

public record McpHostReply(TaskView task,String outcome,String answer,McpHostReport report) { }
