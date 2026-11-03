package dev.myfrei.repopilot.rankingeval;

import org.springframework.context.annotation.*;

@Configuration
public class RankingEvaluationConfiguration {
    @Bean RankingMetrics rankingMetrics() { return new RankingMetrics(); }
}
