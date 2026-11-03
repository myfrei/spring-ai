package dev.myfrei.repopilot.research;

public final class ResearchException extends RuntimeException {
  private final String code;

  public ResearchException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
