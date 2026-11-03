package dev.myfrei.repopilot.cache;

import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name={"repopilot.query.enabled", "repopilot.rag.enabled"}, havingValue="true")
public class QueryCacheConfiguration {
    @Bean QueryPlanCache queryPlanCache() { return new QueryPlanCache(128, Duration.ofMinutes(5), Clock.systemUTC()); }
}
