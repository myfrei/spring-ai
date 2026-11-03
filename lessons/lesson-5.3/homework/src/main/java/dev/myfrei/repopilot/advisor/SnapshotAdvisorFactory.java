package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.RagPlan;
import org.springframework.ai.chat.client.advisor.api.Advisor;

public final class SnapshotAdvisorFactory {
    private SnapshotAdvisorFactory() { }
    public static Advisor create(RagPlan plan) { return new SnapshotRagAdvisor(plan); }
}
