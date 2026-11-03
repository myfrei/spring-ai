package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.controlled.ControlledService;
import dev.myfrei.repopilot.skills.SkillCatalog;
import dev.myfrei.repopilot.ui.SessionCoordinator;
import dev.myfrei.repopilot.verification.VerificationService;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled",
      "repopilot.skills.enabled",
      "repopilot.plans.enabled",
      "repopilot.compaction.enabled",
      "repopilot.verification.enabled",
      "repopilot.research.enabled",
      "repopilot.sessions.enabled",
      "repopilot.capstone.enabled"
    },
    havingValue = "true")
public class CapstoneConfiguration {
  @Bean
  public CapstoneTaskGateway capstoneTaskGateway(
      @Value("${repopilot.capstone.mcp-url:http://127.0.0.1:8093}") String url,
      @Value("${STOCKROOM_MCP_TOKEN:stockroom-local-course-token}") String token) {
    return new StockRoomMcpGateway(URI.create(url), token, Clock.systemUTC());
  }

  @Bean
  public CapstoneService capstoneService(
      CapstoneTaskGateway gateway,
      WorkspaceService workspace,
      AgentStore store,
      SessionCoordinator sessions,
      VerificationService verification,
      ControlledService controlled,
      SkillCatalog skills,
      @Value("${repopilot.verification.image-id}") String image) {
    return new CapstoneService(
        gateway,
        workspace,
        store,
        sessions,
        verification,
        controlled,
        skills,
        Clock.systemUTC(),
        image);
  }
}
