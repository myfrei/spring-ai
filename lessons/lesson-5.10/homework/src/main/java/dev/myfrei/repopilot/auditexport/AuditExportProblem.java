package dev.myfrei.repopilot.auditexport;

public final class AuditExportProblem extends RuntimeException {
  private final String code;

  public AuditExportProblem(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
