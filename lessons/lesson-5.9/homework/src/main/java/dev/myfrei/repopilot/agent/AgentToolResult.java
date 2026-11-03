package dev.myfrei.repopilot.agent;

public record AgentToolResult(boolean ok, String code, String content) {
  public AgentToolResult {
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}") || content == null)
      throw new IllegalArgumentException("Tool result code and JSON content required");
  }

  public static AgentToolResult success(String json) {
    return new AgentToolResult(true, "OK", json);
  }

  public static AgentToolResult error(String code, String json) {
    return new AgentToolResult(false, code, json);
  }
}
