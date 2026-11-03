package dev.myfrei.course.capstonetasks;

import static org.assertj.core.api.Assertions.*;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "stockroom.access-token=capstone-test-token")
@Timeout(20)
class StockRoomMcpServerIT {
  @LocalServerPort int port;

  @Test
  void realSdkInitializesDiscoversExactlyOneReadonlyToolAndReadsClosedTask() {
    var transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint("/mcp")
            .openConnectionOnStartup(false)
            .resumableStreams(false)
            .httpRequestCustomizer(
                (builder, method, uri, body, context) ->
                    builder.setHeader("Authorization", "Bearer capstone-test-token"))
            .build();
    try (var client =
        McpClient.sync(transport)
            .clientInfo(new McpSchema.Implementation("stockroom-it", "1"))
            .initializationTimeout(Duration.ofSeconds(5))
            .requestTimeout(Duration.ofSeconds(5))
            .enableCallToolSchemaCaching(false)
            .build()) {
      var init = client.initialize();
      assertThat(init.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(init.serverInfo().name()).isEqualTo("stockroom-capstone-tasks");
      var listing = client.listTools();
      assertThat(listing.tools()).hasSize(1);
      var tool = listing.tools().getFirst();
      assertThat(tool.name()).isEqualTo("getStockRoomTask");
      assertThat(tool.annotations().readOnlyHint()).isTrue();
      assertThat(tool.inputSchema().get("additionalProperties")).isEqualTo(false);
      var result =
          client.callTool(
              McpSchema.CallToolRequest.builder(tool.name())
                  .arguments(Map.of("taskId", "STOCK-001"))
                  .build());
      assertThat(result.isError()).isFalse();
      assertThat(TaskServerJson.encode(result.structuredContent()))
          .contains("STOCK-001", "STOCKROOM_CONTRACT_V1");
      assertThat(
              client
                  .callTool(
                      McpSchema.CallToolRequest.builder(tool.name())
                          .arguments(Map.of("taskId", "TASK-101"))
                          .build())
                  .isError())
          .isTrue();
      assertThat(
              client
                  .callTool(
                      McpSchema.CallToolRequest.builder(tool.name())
                          .arguments(Map.of("taskId", "STOCK-001", "approved", true))
                          .build())
                  .isError())
          .isTrue();
    }
  }

  @Test
  void actualHttpRejectsMissingBearerAndForeignOriginBeforeSession() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(401);
      var foreign =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
              .header("Authorization", "Bearer capstone-test-token")
              .header("Origin", "http://foreign.invalid")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertThat(client.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(403);
    }
  }
}
