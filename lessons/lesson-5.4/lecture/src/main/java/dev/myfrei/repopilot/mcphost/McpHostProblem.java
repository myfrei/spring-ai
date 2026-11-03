package dev.myfrei.repopilot.mcphost;

public class McpHostProblem extends RuntimeException {
    private final String code;private final McpHostReport report;private final GatewayException.Kind gatewayKind;
    public McpHostProblem(String code,String detail,McpHostReport report,GatewayException.Kind gatewayKind) { super(detail);this.code=code;this.report=report;this.gatewayKind=gatewayKind; }
    public String code() { return code; }
    public McpHostReport report() { return report; }
    public GatewayException.Kind gatewayKind() { return gatewayKind; }
}
