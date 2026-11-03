package dev.myfrei.repopilot.capstone;

public final class CapstoneProblem extends RuntimeException {
  private final String code;

  public CapstoneProblem(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
