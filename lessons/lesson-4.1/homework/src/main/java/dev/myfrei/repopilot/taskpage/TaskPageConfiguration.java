package dev.myfrei.repopilot.taskpage;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Configuration
@ConditionalOnProperty(name="repopilot.tools.enabled",havingValue="true")
public class TaskPageConfiguration {
    @Bean TaskPagePolicy taskPagePolicy(){return new TaskPagePolicy();}
}
