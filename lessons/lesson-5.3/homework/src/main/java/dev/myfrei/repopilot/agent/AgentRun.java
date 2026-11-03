package dev.myfrei.repopilot.agent;
import java.util.UUID;
public record AgentRun(UUID id,UUID conversationId,String status,String stopReason,String answer,AgentRunReport report){}
