package dev.myfrei.repopilot.evidence;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"repopilot.agent.enabled","repopilot.workspace.enabled"},havingValue="true")
public class EvidenceConfiguration {
 @Bean public EvidencePolicy evidencePolicy(){return new EvidencePolicy();}
 @Bean public EvidenceChecker evidenceChecker(WorkspaceService workspace,EvidencePolicy policy){return new EvidenceChecker(workspace,policy);}
}
