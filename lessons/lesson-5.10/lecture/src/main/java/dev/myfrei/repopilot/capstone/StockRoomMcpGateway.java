package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.AgentJson;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual SDK initialize -> discovery -> one tool call. No HTTP/LLM fallback or implicit retry. */
public final class StockRoomMcpGateway implements CapstoneTaskGateway {
  private final String baseUrl, token;
  private final Clock clock;

  public StockRoomMcpGateway(URI url, String token, Clock clock) {
    if (url == null
        || !"http".equals(url.getScheme())
        || !"127.0.0.1".equals(url.getHost())
        || url.getPort() < 1
        || url.getUserInfo() != null
        || url.getQuery() != null
        || url.getFragment() != null
        || !(url.getPath().isEmpty() || url.getPath().equals("/")))
      throw new IllegalArgumentException("Literal loopback MCP base required");
    if (token == null
        || token.isBlank()
        || token.length() > 256
        || token.contains("\r")
        || token.contains("\n")) throw new IllegalArgumentException("Bounded MCP token required");
    this.baseUrl = "http://127.0.0.1:" + url.getPort();
    this.token = token;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public ImportedTask importTask(String taskId) {
    if (!CapstoneTaskContract.TASK.equals(taskId)) throw new CapstoneProblem("CAPSTONE_TASK_ID");
    var initializePermit = new AtomicBoolean(true);
    var transport =
        HttpClientStreamableHttpTransport.builder(baseUrl)
            .endpoint("/mcp")
            .connectTimeout(Duration.ofSeconds(5))
            .openConnectionOnStartup(false)
            .resumableStreams(false)
            .maxResponseSize(16384)
            .httpRequestCustomizer(
                (builder, method, uri, body, context) -> {
                  if (body != null
                      && AgentJson.parse(body) instanceof Map<?, ?> rpc
                      && "initialize".equals(rpc.get("method"))
                      && !initializePermit.compareAndSet(true, false))
                    throw new IllegalStateException("No automatic MCP re-initialization");
                  builder.setHeader("Authorization", "Bearer " + token);
                })
            .build();
    try (var client =
        McpClient.sync(transport)
            .clientInfo(new McpSchema.Implementation("repopilot-capstone", "5.10"))
            .initializationTimeout(Duration.ofSeconds(5))
            .requestTimeout(Duration.ofSeconds(5))
            .enableCallToolSchemaCaching(false)
            .build()) {
      var initialized = client.initialize();
      if (!"2025-11-25".equals(initialized.protocolVersion())
          || initialized.serverInfo() == null
          || !CapstoneTaskContract.SERVER.equals(initialized.serverInfo().name())
          || initialized.capabilities() == null
          || initialized.capabilities().tools() == null)
        throw new CapstoneProblem("CAPSTONE_MCP_CAPABILITIES");
      var listing = client.listTools();
      if (listing == null
          || listing.tools() == null
          || listing.tools().size() != 1
          || listing.nextCursor() != null) throw new CapstoneProblem("CAPSTONE_MCP_DISCOVERY");
      var tool = listing.tools().getFirst();
      var schema =
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "taskId", Map.of("type", "string", "enum", List.of(CapstoneTaskContract.TASK))),
              "required",
              List.of("taskId"),
              "additionalProperties",
              false);
      if (!CapstoneTaskContract.TOOL.equals(tool.name())
          || !schema.equals(tool.inputSchema())
          || tool.annotations() == null
          || !Boolean.TRUE.equals(tool.annotations().readOnlyHint())
          || !Boolean.FALSE.equals(tool.annotations().destructiveHint()))
        throw new CapstoneProblem("CAPSTONE_MCP_TOOL_CONTRACT");
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder(CapstoneTaskContract.TOOL)
                  .arguments(Map.of("taskId", taskId))
                  .build());
      if (result == null
          || Boolean.TRUE.equals(result.isError())
          || result.content() == null
          || result.content().size() != 1
          || !(result.content().getFirst() instanceof McpSchema.TextContent text)
          || AgentJson.bytes(text.text()) > 4096) throw new CapstoneProblem("CAPSTONE_MCP_RESULT");
      var task = CapstoneTaskContract.decode(result.structuredContent());
      if (!AgentJson.parse(text.text()).equals(result.structuredContent()))
        throw new CapstoneProblem("CAPSTONE_MCP_RESULT");
      return new ImportedTask(
          task,
          new McpImportReceipt(
              initialized.serverInfo().name(),
              tool.name(),
              initialized.protocolVersion(),
              1,
              1,
              1,
              CapstoneTaskContract.digest(task),
              clock.instant()));
    } catch (CapstoneProblem expected) {
      throw expected;
    } catch (RuntimeException unavailable) {
      throw new CapstoneProblem("CAPSTONE_MCP_UNAVAILABLE");
    }
  }
}
