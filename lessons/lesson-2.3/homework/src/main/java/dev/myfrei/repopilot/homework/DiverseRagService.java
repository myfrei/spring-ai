package dev.myfrei.repopilot.homework;

import java.util.List;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.chat.*;
import dev.myfrei.repopilot.conversation.ConversationChatService;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
@Service
@ConditionalOnProperty(name="repopilot.rag.enabled",havingValue="true")
public class DiverseRagService {
    private final RagSearcher searcher;private final RagPlanner planner;private final EvidenceSelector selector;
    private final ConversationChatService conversations;private final ChatStreamService model;
    public DiverseRagService(RagSearcher searcher,RagPlanner planner,EvidenceSelector selector,ConversationChatService conversations,ChatStreamService model) { this.searcher=searcher;this.planner=planner;this.selector=selector;this.conversations=conversations;this.model=model; }
    public Preview search(DiverseRagController.Request request) {
        var raw=this.searcher.search(new RagRequest(request.message(),8,request.threshold()));
        var selected=this.selector.select(raw.hits(),request.maxPerDocument(),request.maxTotal(),request.overlapThreshold());
        var kept=selected.stream().map(hit->hit.source().chunkId()).toList();
        return new Preview(raw.hits().size(),raw.hits().stream().filter(hit->!kept.contains(hit.source().chunkId())).map(hit->hit.source().chunkId()).toList(),new RagSearchResult(raw.manifest(),selected));
    }
    public Flux<ChatStreamEvent> stream(String id,DiverseRagController.Request request) {
        return this.conversations.stream(id,request.message(),memory->{
            var plan=this.planner.plan(request.message(),memory.get(id),search(request).selected());
            if(plan.references().isEmpty()) return Flux.just(ChatStreamEvent.rag(plan.report()),ChatStreamEvent.delta(RagChatService.NO_EVIDENCE),ChatStreamEvent.completed());
            var advisor=QuestionAnswerAdvisor.builder(new SelectedDocumentsStore(request.message(),plan.references())).promptTemplate(RagPrompt.template()).searchRequest(SearchRequest.builder().topK(plan.references().size()).build()).build();
            return Flux.concat(Flux.just(ChatStreamEvent.rag(plan.report())),this.model.stream(plan.baseMessages(),advisor));
        });
    }
    public record Preview(int retrievedCount,List<String> removedChunkIds,RagSearchResult selected) { public Preview { removedChunkIds=List.copyOf(removedChunkIds); } }
}
