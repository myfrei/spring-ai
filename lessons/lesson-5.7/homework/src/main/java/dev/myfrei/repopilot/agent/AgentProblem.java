package dev.myfrei.repopilot.agent;

public final class AgentProblem extends RuntimeException {
  private final String code;

  public AgentProblem(String code, String detail) {
    super(detail);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
