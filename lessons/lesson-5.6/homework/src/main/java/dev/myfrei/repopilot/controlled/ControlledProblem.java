package dev.myfrei.repopilot.controlled;

public final class ControlledProblem extends RuntimeException {
  private final String code;

  public ControlledProblem(String code) {
    super("Операция не выполнена: " + code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
