package dev.myfrei.repopilot.advisor;

import java.util.List;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.messages.Message;

public class RagPreparationService {
    private final RagSearcher searcher;
    private final RagPlanner planner;
    public RagPreparationService(RagSearcher searcher, RagPlanner planner) { this.searcher = searcher; this.planner = planner; }
    public RagPlan prepare(RagRequest request, List<Message> history) {
        return prepare(request, history, result -> result);
    }
    public RagPlan prepare(RagRequest request, List<Message> history, java.util.function.UnaryOperator<RagSearchResult> selection) {
        RagSearchResult found = this.searcher.search(request);
        return this.planner.plan(request.message(), List.copyOf(history), selection.apply(found));
    }
    public RagPlan prepareObserved(RagRequest request,List<Message> history,Runnable embeddingAttempt) {
        return this.planner.plan(request.message(),List.copyOf(history),this.searcher.search(request,embeddingAttempt));
    }
}
