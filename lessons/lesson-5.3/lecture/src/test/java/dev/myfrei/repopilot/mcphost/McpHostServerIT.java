package dev.myfrei.repopilot.mcphost;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
/** Opt-in: execute the already packaged independent lesson4.3 Spring MCP HTTP server. */
@Timeout(30)
class McpHostServerIT {
 static PackagedTaskServer server;static URI base;
 @BeforeAll static void start(){server=new PackagedTaskServer();base=server.base();}
 @AfterAll static void stop(){if(server!=null)server.close();}
 @Test void actualSpringAnnotationServerFullResultValidatesAndSessionIsReused(){try(var gateway=new LazyMcpTaskGateway(base,McpWireFixture.TOKEN,Duration.ofSeconds(5))){var first=gateway.readTask("TASK-101");var second=gateway.readTask("TASK-102");assertThat(first.task()).isEqualTo(new TaskCatalog().findTask("TASK-101").orElseThrow().toView());assertThat(first.metadata().initializations()).isOne();assertThat(first.metadata().discoveries()).isOne();assertThat(first.metadata().remoteCalls()).isOne();assertThat(first.metadata().protocolVersion()).isEqualTo("2025-11-25");assertThat(first.metadata().qualifiedTool()).isEqualTo("taskshelf_getTask");assertThat(second.task().id()).isEqualTo("TASK-102");assertThat(second.metadata().initializations()).isZero();assertThat(second.metadata().discoveries()).isZero();assertThat(second.metadata().remoteCalls()).isOne();}}
 @Test void actualBearerFailureIsAuthorizationWithoutRemoteToolCall(){try(var gateway=new LazyMcpTaskGateway(base,"incorrect-test-token",Duration.ofSeconds(2))){assertThatThrownBy(()->gateway.readTask("TASK-101")).isInstanceOf(GatewayException.class).satisfies(e->{var p=(GatewayException)e;assertThat(p.kind()).isEqualTo(GatewayException.Kind.AUTHORIZATION);assertThat(p.metadata().initializations()).isOne();assertThat(p.metadata().discoveries()).isZero();assertThat(p.metadata().remoteCalls()).isZero();});}}
 @Test void everyRemoteTaskMatchesSharedPublicDomainProjection(){try(var gateway=new LazyMcpTaskGateway(base,McpWireFixture.TOKEN,Duration.ofSeconds(5))){for(var task:new TaskCatalog().listTaskViews())assertThat(gateway.readTask(task.id()).task()).isEqualTo(task);}}
}
