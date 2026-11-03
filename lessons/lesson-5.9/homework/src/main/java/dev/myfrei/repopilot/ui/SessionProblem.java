package dev.myfrei.repopilot.ui;

public final class SessionProblem extends RuntimeException {
  private final String code;

  public SessionProblem(String code) {
    super("Сессия: " + code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
