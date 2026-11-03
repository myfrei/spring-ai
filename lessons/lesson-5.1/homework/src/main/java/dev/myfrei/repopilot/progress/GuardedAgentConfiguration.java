package dev.myfrei.repopilot.progress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="repopilot.agent.enabled",havingValue="true")
public class GuardedAgentConfiguration {@Bean public CycleStopRule cycleStopRule(){return new CycleStopRule();}}
