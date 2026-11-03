package dev.myfrei.repopilot.rag;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import dev.myfrei.repopilot.chat.ChatStreamEvent;
import dev.myfrei.repopilot.conversation.SqliteHistory;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@ConditionalOnProperty(name = "repopilot.rag.enabled", havingValue = "true")
public class RagController {
    private final RagIndexPublisher index;
    private final RagSearcher searcher;
    private final RagChatService chat;
    private final SqliteHistory history;
    private final Duration heartbeat;
    public RagController(RagIndexPublisher index, RagSearcher searcher, RagChatService chat, SqliteHistory history,
            @Value("${repopilot.stream.heartbeat}") Duration heartbeat) {
        this.index = index; this.searcher = searcher; this.chat = chat; this.history = history; this.heartbeat = heartbeat;
    }
    @GetMapping("/api/rag") public Map<String, Object> status() {
        return Map.of("workspace", this.index.workspace(), "active", this.index.manifest());
    }
    @PostMapping("/api/rag/publish") public RagIndexPublisher.Publication publish() { return this.index.publish(); }
    @PostMapping("/api/rag/search") public RagSearchResult search(@Valid @RequestBody RagRequest request) { return this.searcher.search(request); }
    @GetMapping(value = "/api/rag/chunks/{fingerprint}/{chunkId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String source(@PathVariable String fingerprint, @PathVariable String chunkId) { return this.searcher.source(fingerprint, chunkId); }
    @PostMapping(value = "/api/conversations/{id}/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> stream(@PathVariable UUID id, @Valid @RequestBody RagRequest request) {
        this.history.get(id.toString());
        var events = this.chat.stream(id.toString(), request).map(event -> ServerSentEvent.builder(event).event(event.type()).build());
        var beats = Flux.interval(this.heartbeat).map(ignored -> ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(events.mergeWith(beats).takeUntil(frame -> frame.data() != null && frame.data().terminal()));
    }
}
