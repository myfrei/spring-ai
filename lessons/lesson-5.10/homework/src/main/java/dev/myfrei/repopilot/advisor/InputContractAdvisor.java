package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.RagProblem;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;

public class InputContractAdvisor implements BaseAdvisor {
    private final int order;
    public InputContractAdvisor() { this(-100); }
    public InputContractAdvisor(int order) { this.order = order; }
    @Override public int getOrder() { return this.order; }
    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (request.context().containsKey(SnapshotRagAdvisor.APPLIED)) throw new RagProblem("ADVISOR_ORDER", "InputContractAdvisor должен выполняться до расширения RAG.");
        String text = request.prompt().getUserMessage().getText();
        if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("Invalid original question");
        return request;
    }
    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return response; }
}
