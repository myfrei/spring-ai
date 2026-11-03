package dev.myfrei.repopilot.skills;

/** Stable public codes; raw filesystem failures are not returned to the model. */
public final class SkillException extends RuntimeException {
  private final String code;

  public SkillException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
