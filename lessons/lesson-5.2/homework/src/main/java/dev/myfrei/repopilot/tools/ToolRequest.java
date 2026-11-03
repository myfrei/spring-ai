package dev.myfrei.repopilot.tools;

public record ToolRequest(String taskId,String question) {
    public ToolRequest {
        if (taskId==null||!taskId.matches("TASK-[0-9]{3}")||question==null||question.isBlank()||question.length()>1000)
            throw new IllegalArgumentException("taskId and question 1..1000 required");
    }
}
