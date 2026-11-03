package dev.myfrei.repopilot.agent;
import java.util.UUID;
public record AgentTurn(UUID runId,String question,String answer){}
