package dev.myfrei.repopilot.verification;

public final class VerificationProblem extends RuntimeException {
  private final String code;

  public VerificationProblem(String code) {
    super("Проверка не выполнена: " + code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
