package dev.myfrei.repopilot.conversation;

public record Turn(long id, String conversationId, String question, String answer, String status, String errorCode) { }
