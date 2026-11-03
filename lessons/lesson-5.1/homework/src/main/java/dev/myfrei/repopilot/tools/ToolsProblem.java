package dev.myfrei.repopilot.tools;

public class ToolsProblem extends RuntimeException {
    private final String code; private final ToolRunReport report;
    public ToolsProblem(String code,String detail,ToolRunReport report) { super(detail);this.code=code;this.report=report; }
    public String code() { return code; }
    public ToolRunReport report() { return report; }
}
