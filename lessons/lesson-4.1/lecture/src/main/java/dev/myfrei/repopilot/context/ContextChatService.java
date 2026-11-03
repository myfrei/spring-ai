package dev.myfrei.repopilot.context;

import dev.myfrei.repopilot.chat.ChatStreamEvent;
import dev.myfrei.repopilot.chat.ChatStreamService;
import dev.myfrei.repopilot.conversation.ConversationChatService;
import dev.myfrei.repopilot.conversation.ConversationMemoryFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ContextChatService {
    private final CorpusCatalog corpus;
    private final ContextAssembler assembler;
    private final ConversationChatService conversations;
    private final ConversationMemoryFactory memories;
    private final ChatStreamService model;

    public ContextChatService(CorpusCatalog corpus, ContextAssembler assembler, ConversationChatService conversations,
            ConversationMemoryFactory memories, ChatStreamService model) {
        this.corpus = corpus; this.assembler = assembler; this.conversations = conversations;
        this.memories = memories; this.model = model;
    }

    public ContextReport preview(String id, String question, ContextMode mode) {
        return this.assembler.assemble(question, mode, this.memories.create(id).get(id), this.corpus.snapshot()).report();
    }

    public Flux<ChatStreamEvent> stream(String id, String question, ContextMode mode) {
        return this.conversations.stream(id, question, memory -> {
            ContextPlan plan = this.assembler.assemble(question, mode, memory.get(id), this.corpus.snapshot());
            return Flux.concat(Flux.just(ChatStreamEvent.context(plan.report())),
                    this.model.stream(plan.messages()));
        });
    }
}
