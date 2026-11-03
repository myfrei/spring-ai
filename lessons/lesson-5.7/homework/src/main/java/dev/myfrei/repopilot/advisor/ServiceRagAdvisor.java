package dev.myfrei.repopilot.advisor;

import java.util.ArrayList;
import java.util.List;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;

public class ServiceRagAdvisor implements BaseAdvisor {
    private final RagPreparationService preparation;
    private final int topK;
    private final double threshold;
    private final java.util.function.UnaryOperator<RagSearchResult> selection;
    public ServiceRagAdvisor(RagPreparationService preparation, int topK, double threshold) { this(preparation, topK, threshold, result -> result); }
    public ServiceRagAdvisor(RagPreparationService preparation, int topK, double threshold, java.util.function.UnaryOperator<RagSearchResult> selection) {
        new RagRequest("validate", topK, threshold); this.preparation = preparation; this.topK = topK; this.threshold = threshold; this.selection = selection;
    }
    @Override public int getOrder() { return 0; }
    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (request.context().containsKey(SnapshotRagAdvisor.APPLIED)) throw new RagProblem("DUPLICATE_RAG", "Не подключайте два RAG advisor к одному запросу.");
        var input = request.prompt().getInstructions();
        if (input.isEmpty() || input.getLast().getMessageType() != MessageType.USER) throw new IllegalArgumentException("Last message must be user");
        var history = new ArrayList<Message>();
        for (int i = 0; i < input.size() - 1; i++) if (input.get(i).getMessageType() != MessageType.SYSTEM) history.add(input.get(i));
        var query = new RagRequest(input.getLast().getText(), this.topK, this.threshold);
        var plan = this.preparation.prepare(query, history, this.selection);
        if (plan.references().isEmpty()) throw new RagProblem("NO_EVIDENCE", "По текущим параметрам подходящих фрагментов нет. Chat-модель не вызывалась.");
        return SnapshotRagAdvisor.apply(request, plan, false);
    }
    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return SnapshotRagAdvisor.attachReport(response); }
}
