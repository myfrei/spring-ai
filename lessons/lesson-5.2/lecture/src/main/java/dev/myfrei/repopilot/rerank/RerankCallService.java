package dev.myfrei.repopilot.rerank;

import dev.myfrei.repopilot.advisor.SnapshotAdvisorFactory;
import dev.myfrei.repopilot.query.QueryRagPipeline;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

public class RerankCallService {
    public record Reply(String answer,String outcome,RerankPipeline.Report preparation,double totalMs,Integer providerInputTokens,Integer providerOutputTokens) { }
    private final RerankPipeline pipeline; private final ChatClient client;
    public RerankCallService(RerankPipeline pipeline,ChatClient.Builder builder) { this.pipeline=pipeline; this.client=builder.build(); }
    public Reply call(RerankRequest request) { long start=System.nanoTime(); return generate(pipeline.prepare(request),start); }
    public Reply callShared(RerankRequest request,QueryRagPipeline.Retrieved retrieved) { long start=System.nanoTime(); return generate(pipeline.prepareShared(request,retrieved),start); }
    private Reply generate(RerankPipeline.Prepared prepared,long start) {
        if (prepared.rag().references().isEmpty()) return new Reply(RagChatService.NO_EVIDENCE,"NO_EVIDENCE",prepared.report(),(System.nanoTime()-start)/1e6,null,null);
        ChatResponse response;
        try { response=client.prompt().messages(prepared.rag().baseMessages()).advisors(SnapshotAdvisorFactory.create(prepared.rag())).call().chatResponse(); }
        catch (RagProblem error) { throw error; }
        catch (RuntimeException error) { throw new RagProblem("RERANK_GENERATION_UNAVAILABLE","Не удалось получить финальный ответ."); }
        if (response==null||response.getResult()==null||!"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) throw new RagProblem("UPSTREAM_INCOMPLETE","Финальный ответ не подтвердил завершение.");
        String answer=response.getResult().getOutput().getText();
        if (answer==null||answer.isBlank()) throw new RagProblem("EMPTY_RESPONSE","Модель вернула пустой финальный ответ.");
        var usage=response.getMetadata().getUsage();
        return new Reply(answer,"COMPLETED",prepared.report(),(System.nanoTime()-start)/1e6,usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens());
    }
}
