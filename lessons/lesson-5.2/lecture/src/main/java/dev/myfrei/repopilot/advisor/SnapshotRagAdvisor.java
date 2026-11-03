package dev.myfrei.repopilot.advisor;

import java.util.ArrayList;
import java.util.HashMap;
import dev.myfrei.repopilot.evaluation.PromptFingerprint;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

public class SnapshotRagAdvisor implements BaseAdvisor {
    public static final String APPLIED = "repopilot.rag.applied";
    public static final String REPORT = "repopilot.rag.report";
    private final RagPlan plan;
    public SnapshotRagAdvisor(RagPlan plan) {
        if (plan.references().isEmpty() || plan.report().estimatedInputTokens() > plan.report().inputBudget()) throw new IllegalArgumentException("Prepared nonempty budgeted plan required");
        this.plan = plan;
    }
    @Override public int getOrder() { return 0; }
    @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return apply(request, this.plan, true);
    }
    public static ChatClientRequest apply(ChatClientRequest request, RagPlan plan, boolean requireSameInput) {
        if (request.context().containsKey(APPLIED)) throw new RagProblem("DUPLICATE_RAG", "Контекст уже добавлен другим RAG advisor.");
        if (requireSameInput && !PromptFingerprint.of(request.prompt().getInstructions()).equals(PromptFingerprint.of(plan.baseMessages()))) {
            throw new RagProblem("PROMPT_CHANGED", "Сообщения изменились после расчёта бюджета. Подготовьте план заново.");
        }
        var expanded = new ArrayList<>(plan.baseMessages());
        String question = expanded.getLast().getText();
        expanded.set(expanded.size() - 1, new UserMessage(RagPrompt.render(question, plan.references())));
        var context = new HashMap<>(request.context()); context.put(APPLIED, true); context.put(REPORT, plan.report());
        return request.mutate().prompt(new Prompt(expanded, request.prompt().getOptions())).context(context).build();
    }
    @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return attachReport(response); }
    public static ChatClientResponse attachReport(ChatClientResponse response) {
        if (response.chatResponse() == null) return response;
        var chat = ChatResponse.builder().from(response.chatResponse()).metadata(REPORT, response.context().get(REPORT)).build();
        return ChatClientResponse.builder().chatResponse(chat).context(response.context()).build();
    }
}
