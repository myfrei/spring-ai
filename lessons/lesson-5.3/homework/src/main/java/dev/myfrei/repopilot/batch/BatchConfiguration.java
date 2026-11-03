package dev.myfrei.repopilot.batch;

import dev.myfrei.repopilot.controlled.ControlledService;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {
      "repopilot.agent.enabled",
      "repopilot.workspace.enabled",
      "repopilot.controlled.enabled"
    },
    havingValue = "true")
public class BatchConfiguration {
  @Bean
  public ProposalBatchPolicy proposalBatchPolicy() {
    return new ProposalBatchPolicy();
  }

  @Bean
  public BatchChecker batchChecker(
      ControlledService proposals, WorkspaceService workspace, ProposalBatchPolicy policy) {
    return new BatchChecker(proposals, workspace, Clock.systemUTC(), policy);
  }
}
