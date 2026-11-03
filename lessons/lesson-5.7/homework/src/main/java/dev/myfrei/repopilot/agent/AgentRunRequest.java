package dev.myfrei.repopilot.agent;

public record AgentRunRequest(String question) {
  public AgentRunRequest {
    if (question == null || question.isBlank() || question.length() > 1000)
      throw new IllegalArgumentException("question 1..1000 required");
  }
}
