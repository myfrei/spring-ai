package dev.myfrei.repopilot.mcphost;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.*;

/** One lazy, request-time SDK session. Never retries the failed read in the current request. */
public final class LazyMcpTaskGateway implements RemoteTaskGateway,AutoCloseable {
    private final String baseUrl,token;private final Duration timeout;private final Object lock=new Object();
    private McpSyncClient client;private String protocol="";private boolean discovered;
    public LazyMcpTaskGateway(URI baseUrl,String token,Duration timeout) {
        if (!"http".equals(baseUrl.getScheme())||!"127.0.0.1".equals(baseUrl.getHost())||baseUrl.getPort()<1
                ||baseUrl.getUserInfo()!=null||baseUrl.getQuery()!=null||baseUrl.getFragment()!=null
                ||!(baseUrl.getPath().isEmpty()||baseUrl.getPath().equals("/"))) throw new IllegalArgumentException("Fixed literal-loopback MCP base URL required");
        if (token==null||token.isBlank()||token.length()>256||token.contains("\r")||token.contains("\n")
                ||timeout==null||timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(5))>0) throw new IllegalArgumentException("Bounded MCP timeout/token required");
        this.baseUrl="http://127.0.0.1:"+baseUrl.getPort();this.token=token;this.timeout=timeout;
    }
    @Override public ValidatedTaskRead readTask(String requestedId) {
        long start=System.nanoTime();
        synchronized (lock) {
            var counts=new int[3];
            if (new TaskCatalog().findTask(requestedId).isEmpty()) throw failure(GatewayException.Kind.INVALID_ARGUMENTS,"MCP_INVALID_TASK","taskId вне разрешённого каталога.",counts,start);
            try {
                if (client==null) {
                    // SDK2.1.0 has no switch for LifecycleInitializer automatic404 re-initialization.
                    // One initialize HTTP dispatch is allowed per explicitly created lazy client.
                    var initializePermit=new AtomicBoolean(true);
                    var mapper=JsonMapper.builder().build();
                    var transport=HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/mcp")
                            .connectTimeout(timeout).openConnectionOnStartup(false).resumableStreams(false).maxResponseSize(65536)
                            .httpRequestCustomizer((builder,method,uri,body,context)->{
                                if (body!=null&&"initialize".equals(mapper.readTree(body).path("method").asText())&&!initializePermit.compareAndSet(true,false))
                                    throw new IllegalStateException("Host forbids automatic MCP re-initialization; next user request creates a new client");
                                builder.setHeader("Authorization","Bearer "+token);
                            }).build();
                    client=McpClient.sync(transport).clientInfo(new McpSchema.Implementation("repopilot-host","4.4"))
                            .initializationTimeout(timeout).requestTimeout(timeout).enableCallToolSchemaCaching(false).build();
                }
                if (!client.isInitialized()) {
                    counts[0]++;var initialized=client.initialize();protocol=initialized.protocolVersion();
                    if (initialized.capabilities()==null||initialized.capabilities().tools()==null) throw failure(GatewayException.Kind.PROTOCOL,"MCP_TOOLS_UNAVAILABLE","Сервер не объявил tools capability.",counts,start);
                }
                if (!discovered) {
                    counts[1]++;var listing=client.listTools();validateDiscovery(listing,counts,start);discovered=true;
                }
                counts[2]++;
                var result=client.callTool(McpSchema.CallToolRequest.builder("getTask").arguments(Map.of("taskId",requestedId)).build());
                var metadata=metadata(counts,start);
                return new ValidatedTaskRead(RemoteTaskValidator.decode(result,requestedId,metadata),metadata);
            } catch (GatewayException error) {
                if (error.kind()==GatewayException.Kind.AVAILABILITY||error.kind()==GatewayException.Kind.AUTHORIZATION||!discovered) invalidate();
                throw error;
            } catch (RuntimeException error) {
                var kind=classify(error);var failure=failure(kind,code(kind),detail(kind),counts,start);
                // A stale/closed session must not poison future half-open probes.
                if (kind==GatewayException.Kind.AVAILABILITY||kind==GatewayException.Kind.AUTHORIZATION) invalidate();
                else if (!discovered) invalidate();
                throw failure;
            }
        }
    }
    private void validateDiscovery(McpSchema.ListToolsResult listing,int[] counts,long start) {
        if (listing==null||listing.tools()==null||listing.tools().size()>8||(listing.nextCursor()!=null&&!listing.nextCursor().isBlank()))
            throw failure(GatewayException.Kind.PROTOCOL,"MCP_DISCOVERY_INVALID","Ожидался ограниченный полный список tools.",counts,start);
        var names=new HashSet<String>();McpSchema.Tool selected=null;
        for (var tool:listing.tools()) { if (tool==null||!names.add(tool.name())) throw failure(GatewayException.Kind.PROTOCOL,"MCP_DISCOVERY_INVALID","Повторённые имена инструментов недопустимы.",counts,start);if (tool.name().equals("getTask")) selected=tool; }
        if (selected==null||selected.inputSchema()==null||!"object".equals(selected.inputSchema().get("type"))
                ||!Boolean.FALSE.equals(selected.inputSchema().get("additionalProperties"))||!List.of("taskId").equals(selected.inputSchema().get("required"))
                ||!(selected.inputSchema().get("properties") instanceof Map<?,?> properties)||!properties.keySet().equals(Set.of("taskId"))
                ||!(properties.get("taskId") instanceof Map<?,?> task)||!"string".equals(task.get("type"))
                ||selected.annotations()==null||!Boolean.TRUE.equals(selected.annotations().readOnlyHint())||!Boolean.FALSE.equals(selected.annotations().destructiveHint()))
            throw failure(GatewayException.Kind.PROTOCOL,"MCP_TOOL_CONTRACT_INVALID","getTask не соответствует разрешённому read-only контракту.",counts,start);
    }
    private RemoteReadMetadata metadata(int[] counts,long start) { return new RemoteReadMetadata(RemoteReadMetadata.SERVER,RemoteReadMetadata.TOOL,protocol,counts[0],counts[1],counts[2],(System.nanoTime()-start)/1e6); }
    private GatewayException failure(GatewayException.Kind kind,String code,String detail,int[] counts,long start) { return new GatewayException(kind,code,detail,metadata(counts,start)); }
    static GatewayException.Kind classify(Throwable error) {
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) if (cause instanceof McpHttpClientTransportAuthorizationException) return GatewayException.Kind.AUTHORIZATION;
        for (Throwable cause=error;cause!=null;cause=cause.getCause())
            if (cause instanceof McpTransportSessionNotFoundException||cause instanceof McpTransportSessionClosedException||cause instanceof TimeoutException
                    ||cause instanceof java.io.IOException||cause instanceof java.nio.channels.UnresolvedAddressException) return GatewayException.Kind.AVAILABILITY;
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) if (cause instanceof McpError) return GatewayException.Kind.PROTOCOL;
        String text=McpError.aggregateExceptionMessages(error).toLowerCase(Locale.ROOT);
        // SDK2.1.0 closes pending exchanges on HTTP404 and loses the typed cause.
        if (text.contains("mcp session with server terminated")) return GatewayException.Kind.AVAILABILITY;
        if (text.contains("maxresponse")||text.contains("maximum response")||text.contains("response size")) return GatewayException.Kind.INVALID_RESPONSE;
        if (text.contains("401")||text.contains("403")) return GatewayException.Kind.AUTHORIZATION;
        if (text.contains("timeout")||text.contains("timed out")||text.contains("connection")||text.contains("500")||text.contains("503")) return GatewayException.Kind.AVAILABILITY;
        return GatewayException.Kind.PROTOCOL;
    }
    private static String code(GatewayException.Kind kind) { return switch(kind) { case AVAILABILITY->"MCP_UNAVAILABLE";case AUTHORIZATION->"MCP_AUTHORIZATION_FAILED";case INVALID_RESPONSE->"MCP_INVALID_RESPONSE";default->"MCP_PROTOCOL_FAILED"; }; }
    private static String detail(GatewayException.Kind kind) { return switch(kind) { case AVAILABILITY->"MCP transport/session недоступен. Автоматического повтора в текущем запросе нет.";case AUTHORIZATION->"MCP отклонил авторизацию; проверьте локальную конфигурацию токена.";default->"MCP ответ не прошёл проверку протокола; payload не раскрыт."; }; }
    private void invalidate() { var old=client;client=null;discovered=false;protocol="";if (old!=null) try { old.close(); } catch (RuntimeException ignored) { } }
    @Override public void close() { synchronized(lock) { invalidate(); } }
}
