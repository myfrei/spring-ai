package dev.myfrei.repopilot.rag;

import dev.myfrei.repopilot.chat.*;
import dev.myfrei.repopilot.conversation.ConversationChatService;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@ConditionalOnProperty(name = "repopilot.rag.enabled", havingValue = "true")
public class RagChatService {
    public static final String NO_EVIDENCE = "По текущим настройкам поиска подходящие фрагменты не найдены. Уточните вопрос или проверьте индекс и порог поиска.";
    private final ConversationChatService conversations;
    private final RagSearcher searcher;
    private final RagPlanner planner;
    private final ChatStreamService model;
    public RagChatService(ConversationChatService conversations, RagSearcher searcher, RagPlanner planner, ChatStreamService model) {
        this.conversations = conversations; this.searcher = searcher; this.planner = planner; this.model = model;
    }
    public Flux<ChatStreamEvent> stream(String id, RagRequest request) {
        return this.conversations.stream(id, request.message(), memory -> {
            var plan = this.planner.plan(request.message(), memory.get(id), this.searcher.search(request));
            if (plan.references().isEmpty()) return Flux.just(ChatStreamEvent.rag(plan.report()),
                    ChatStreamEvent.delta(NO_EVIDENCE), ChatStreamEvent.completed());
            var advisor = QuestionAnswerAdvisor.builder(new SelectedDocumentsStore(request.message(), plan.references()))
                    .promptTemplate(RagPrompt.template()).searchRequest(SearchRequest.builder().topK(plan.references().size()).build()).build();
            return Flux.concat(Flux.just(ChatStreamEvent.rag(plan.report())), this.model.stream(plan.baseMessages(), advisor));
        });
    }
}
