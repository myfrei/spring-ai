package dev.myfrei.repopilot.mcphost;
import dev.myfrei.repopilot.context.Utf8TokenEstimator;
import dev.myfrei.repopilot.rag.RagSearcher;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
class McpHostConfigurationTest {
 private final ApplicationContextRunner runner=new ApplicationContextRunner()
  .withInitializer(context->context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
  .withUserConfiguration(McpHostConfiguration.class,McpHostController.class)
  .withBean(RagSearcher.class,()->org.mockito.Mockito.mock(RagSearcher.class))
  .withBean(Utf8TokenEstimator.class,Utf8TokenEstimator::new)
  .withBean(ChatClient.Builder.class,()->ChatClient.builder(new HostFixtures.Model()))
  .withBean(RestClient.Builder.class,RestClient::builder)
  .withPropertyValues("repopilot.system-prompt=Server policy","repopilot.context.window-tokens=4096","repopilot.context.safety-margin=256","spring.ai.ollama.chat.model=fixture","repopilot.mcp-host.server-url=http://127.0.0.1:1","spring.ai.ollama.base-url=http://127.0.0.1:1");
 @Test void productionHostConfigurationBootsWithRemoteServerOfflineWithoutStandaloneContextBudgetBean(){runner.withPropertyValues("repopilot.mcp-host.enabled=true","repopilot.rag.enabled=true","repopilot.context.output-reserve=64").run(context->{assertThat(context).hasNotFailed().hasSingleBean(McpHostEngine.class).hasSingleBean(RemoteTaskGateway.class).hasSingleBean(McpHostController.class);assertThat(context).doesNotHaveBean(dev.myfrei.repopilot.context.ContextBudget.class);});}
 @Test void disabledHostDoesNotCreateGatewayOrEngine(){runner.withPropertyValues("repopilot.mcp-host.enabled=false","repopilot.rag.enabled=true").run(context->assertThat(context).hasNotFailed().doesNotHaveBean(McpHostEngine.class).doesNotHaveBean(RemoteTaskGateway.class));}
 @Test void hostRequiresExplicitRagFeature(){runner.withPropertyValues("repopilot.mcp-host.enabled=true","repopilot.rag.enabled=false").run(context->assertThat(context).hasNotFailed().doesNotHaveBean(McpHostEngine.class).doesNotHaveBean(RemoteTaskGateway.class));}
}
