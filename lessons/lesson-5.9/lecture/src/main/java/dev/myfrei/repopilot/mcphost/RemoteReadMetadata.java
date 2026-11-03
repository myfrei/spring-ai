package dev.myfrei.repopilot.mcphost;

public record RemoteReadMetadata(String serverName,String qualifiedTool,String protocolVersion,int initializations,
        int discoveries,int remoteCalls,double elapsedMs) {
    public static final String SERVER="taskshelf",TOOL="taskshelf_getTask";
    public static RemoteReadMetadata empty() { return new RemoteReadMetadata(SERVER,TOOL,"",0,0,0,0); }
}
