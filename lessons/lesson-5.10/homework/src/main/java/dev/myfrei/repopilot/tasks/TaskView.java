package dev.myfrei.repopilot.tasks;

public record TaskView(String id,String title,Task.Status status,String summary) { }
