package dev.myfrei.repopilot.mcphost;

public record McpHostRequest(String taskId,String question) {
    public McpHostRequest {
        if (taskId==null||!taskId.matches("TASK-[0-9]{3}")||question==null||question.isBlank()||question.length()>1000)
            throw new IllegalArgumentException("Known taskId and question 1..1000 required");
    }
}
