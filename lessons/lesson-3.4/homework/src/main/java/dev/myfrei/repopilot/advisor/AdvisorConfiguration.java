package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration
@ConditionalOnProperty(name = "repopilot.rag.enabled", havingValue = "true")
public class AdvisorConfiguration {
    @Bean RagPreparationService ragPreparationService(RagSearcher searcher, RagPlanner planner) { return new RagPreparationService(searcher, planner); }
    @Bean AdvisorCallService advisorCallService(ChatClient.Builder builder, RagPreparationService preparation) { return new AdvisorCallService(builder, preparation); }
}
