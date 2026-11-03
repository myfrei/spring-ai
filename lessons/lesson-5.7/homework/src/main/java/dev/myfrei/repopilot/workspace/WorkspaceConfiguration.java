package dev.myfrei.repopilot.workspace;

import dev.myfrei.repopilot.agent.AgentTool;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"repopilot.agent.enabled", "repopilot.workspace.enabled"},
    havingValue = "true")
public class WorkspaceConfiguration {
  @Bean
  public WorkspaceService workspaceService(
      @Value("${repopilot.workspace.root:./workspace-fixture}") String root) {
    return new WorkspaceService(Path.of(root));
  }

  @Bean
  public AgentTool workspaceListFilesTool(WorkspaceService service) {
    return WorkspaceAgentTools.create(service).get(0);
  }

  @Bean
  public AgentTool workspaceSearchTextTool(WorkspaceService service) {
    return WorkspaceAgentTools.create(service).get(1);
  }

  @Bean
  public AgentTool workspaceReadFileTool(WorkspaceService service) {
    return WorkspaceAgentTools.create(service).get(2);
  }

  @Bean
  public AgentTool workspaceGitStatusTool(WorkspaceService service) {
    return WorkspaceAgentTools.create(service).get(3);
  }

  @Bean
  public AgentTool workspaceGitDiffTool(WorkspaceService service) {
    return WorkspaceAgentTools.create(service).get(4);
  }
}
