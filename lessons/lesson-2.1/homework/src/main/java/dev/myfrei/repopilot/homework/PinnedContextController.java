package dev.myfrei.repopilot.homework;

import java.util.*;
import dev.myfrei.repopilot.chat.*;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.conversation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
public class PinnedContextController {
    private final PinnedContextAssembler planner; private final CorpusCatalog corpus; private final ConversationMemoryFactory memories;
    private final ConversationChatService conversations; private final ChatStreamService model;
    public PinnedContextController(PinnedContextAssembler planner, CorpusCatalog corpus, ConversationMemoryFactory memories, ConversationChatService conversations, ChatStreamService model) {
        this.planner = planner; this.corpus = corpus; this.memories = memories; this.conversations = conversations; this.model = model;
    }
    @PostMapping("/api/conversations/{id}/context/pinned/preview")
    public ContextReport preview(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return this.planner.assemble(request.message(), request.mode(), this.memories.create(id.toString()).get(id.toString()), this.corpus.snapshot(), request.pinnedPairs()).report();
    }
    @PostMapping(value="/api/conversations/{id}/context/pinned/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return this.conversations.stream(id.toString(), request.message(), memory -> {
            var plan = this.planner.assemble(request.message(), request.mode(), memory.get(id.toString()), this.corpus.snapshot(), request.pinnedPairs());
            return Flux.concat(Flux.just(ChatStreamEvent.context(plan.report())), this.model.stream(plan.messages()));
        }).map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }
    public record Request(@NotBlank @Size(max=4000) String message, ContextMode mode, List<Integer> pinnedPairs) {
        public Request { if (mode == null) mode=ContextMode.CHAT; pinnedPairs = pinnedPairs == null ? List.of() : List.copyOf(pinnedPairs); }
    }
}
