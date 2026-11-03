package dev.myfrei.repopilot.researchplan;

/** A server evidence boundary failed; no partial plan is returned. */
public final class ResearchReadPlanProblem extends RuntimeException {
  private final String code;

  public ResearchReadPlanProblem(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
