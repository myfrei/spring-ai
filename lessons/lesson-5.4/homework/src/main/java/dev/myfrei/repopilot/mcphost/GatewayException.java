package dev.myfrei.repopilot.mcphost;

public class GatewayException extends RuntimeException {
    public enum Kind { AVAILABILITY, AUTHORIZATION, PROTOCOL, REMOTE_TOOL, INVALID_RESPONSE, INVALID_ARGUMENTS }
    private final Kind kind;private final String code;private final RemoteReadMetadata metadata;
    public GatewayException(Kind kind,String code,String detail,RemoteReadMetadata metadata) {
        super(detail);this.kind=kind;this.code=code;this.metadata=metadata==null?RemoteReadMetadata.empty():metadata;
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
    public RemoteReadMetadata metadata() { return metadata; }
}
