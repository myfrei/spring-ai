package dev.myfrei.repopilot.mcphost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
/** Full production configuration: neither MCP nor PostgreSQL is available at boot. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
 "repopilot.mcp-host.enabled=true","repopilot.rag.enabled=true","repopilot.context.output-reserve=64",
 "repopilot.mcp-host.server-url=http://127.0.0.1:1","repopilot.rag.jdbc-url=jdbc:postgresql://127.0.0.1:1/offline",
 "spring.ai.ollama.base-url=http://127.0.0.1:1"})
@ActiveProfiles("test")
class McpHostOfflineBootTest {
 @Autowired McpHostEngine engine;@Autowired RemoteTaskGateway gateway;
 @Test void applicationCreatesRealProductionHostWithoutConnectingToExternalServices(){assertThat(engine).isNotNull();assertThat(gateway).isInstanceOf(LazyMcpTaskGateway.class);}
}
