package dev.myfrei.repopilot.attention;

public final class AttentionProblem extends RuntimeException {
  private final String code;

  public AttentionProblem(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
