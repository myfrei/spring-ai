package dev.myfrei.repopilot.readiness;
import org.springframework.context.annotation.*;
@Configuration
@Profile("release-homework")
public class ReleaseReadinessConfiguration {
    @Bean ReleaseReadinessPolicy releaseReadinessPolicy(){return new ReleaseReadinessPolicy();}
}
