package dev.myfrei.repopilot.compaction;

public final class CompactionProblem extends RuntimeException {
  private final String code;

  public CompactionProblem(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
