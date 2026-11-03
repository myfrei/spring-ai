package dev.myfrei.repopilot.advisor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.ingestion.EmbeddingIdentity;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public final class AdvisorFixtures {
    public static final String QUESTION = "Порт {literal}?";
    public static RagSearchResult retrieval() {
        var identity = new EmbeddingIdentity("fixture", "a".repeat(64), 3, "fixture-v1");
        var manifest = new RagManifest("taskshelf", "b".repeat(64), "c".repeat(64), identity, 2);
        return new RagSearchResult(manifest, List.of(
            new RagHit(new RagSource("S1", "d".repeat(64), "runbook", "RUNBOOK.md", "Runbook", "Start", 1, 2, "e".repeat(64), .95), "Порт 8091. {\"id\":\"TS-42\"}"),
            new RagHit(new RagSource("S2", "f".repeat(64), "overview", "README.md", "Overview", "Owner", 1, 2, "0".repeat(64), .8), "Владелец — Atlas.")));
    }
    public static RagPlanner planner() { return new RagPlanner("Server policy", new ContextBudget(4096, 256, 256), new Utf8TokenEstimator()); }
    public static RagPlan plan() { return planner().plan(QUESTION, List.of(), retrieval()); }
    public static ChatResponse response(String text, String reason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(reason).build())));
    }
    public static class RecordingModel implements ChatModel {
        public final AtomicInteger calls = new AtomicInteger(); public final AtomicInteger streams = new AtomicInteger(); public volatile Prompt received;
        @Override public ChatResponse call(Prompt prompt) { this.received = prompt; this.calls.incrementAndGet(); return response("Порт 8091 [S1].", "stop"); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            this.received = prompt; this.streams.incrementAndGet();
            return Flux.just(response("Порт ", ""), response("8091 ", ""), response("[S1].", ""), response("", "stop"));
        }
    }
}
