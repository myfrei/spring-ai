package dev.myfrei.repopilot.circuit;

import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"repopilot.mcp-host.enabled","repopilot.rag.enabled"},havingValue="true")
public class CircuitConfiguration {
    @Bean public CircuitGate circuitGate(){return new CircuitGate(3,Duration.ofSeconds(30),Clock.systemUTC());}
}
