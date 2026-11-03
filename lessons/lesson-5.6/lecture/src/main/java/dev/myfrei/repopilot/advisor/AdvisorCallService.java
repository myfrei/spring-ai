package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

public class AdvisorCallService {
    private final ChatClient client;
    private final RagPreparationService preparation;
    public AdvisorCallService(ChatClient.Builder builder, RagPreparationService preparation) { this.client = builder.build(); this.preparation = preparation; }
    public Reply call(RagRequest request) { return call(request, result -> result); }
    public Reply call(RagRequest request, java.util.function.UnaryOperator<RagSearchResult> selection) {
        Advisor rag = new ServiceRagAdvisor(this.preparation, request.topK(), request.threshold(), selection);
        var response = this.client.prompt().user(request.message()).advisors(new InputContractAdvisor(), rag).call().chatResponse();
        if (response == null || response.getResult() == null) throw new RagProblem("EMPTY_RESPONSE", "Chat-модель не вернула результат.");
        if (!"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) throw new RagProblem("UPSTREAM_INCOMPLETE", "Chat-модель не подтвердила завершение ответа.");
        String answer = response.getResult().getOutput().getText();
        if (answer == null || answer.isBlank()) throw new RagProblem("EMPTY_RESPONSE", "Chat-модель вернула пустой ответ.");
        Object report = response.getMetadata().get(SnapshotRagAdvisor.REPORT);
        if (!(report instanceof RagReport ragReport)) throw new IllegalStateException("Missing RAG report");
        return new Reply(answer, ragReport);
    }
    public record Reply(String answer, RagReport rag) { }
}
