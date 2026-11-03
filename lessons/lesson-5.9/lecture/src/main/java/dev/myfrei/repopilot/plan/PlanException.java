package dev.myfrei.repopilot.plan;

public final class PlanException extends RuntimeException {
  private final String code;

  public PlanException(String code) {
    super("План не изменён: " + code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
