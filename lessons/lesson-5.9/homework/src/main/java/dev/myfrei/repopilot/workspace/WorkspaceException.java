package dev.myfrei.repopilot.workspace;

/** Expected local read failure. Messages intentionally contain no raw paths or payload. */
public final class WorkspaceException extends RuntimeException {
  private final String code;

  public WorkspaceException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return this.code;
  }
}
