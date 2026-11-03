package dev.myfrei.repopilot.context;

import java.time.Duration;
import java.util.UUID;
import dev.myfrei.repopilot.chat.ChatStreamEvent;
import dev.myfrei.repopilot.conversation.SqliteHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ContextController {
    private final CorpusCatalog corpus;
    private final ContextChatService chat;
    private final SqliteHistory history;
    private final Duration heartbeat;

    public ContextController(CorpusCatalog corpus, ContextChatService chat, SqliteHistory history,
            @Value("${repopilot.stream.heartbeat}") Duration heartbeat) {
        this.corpus = corpus; this.chat = chat; this.history = history; this.heartbeat = heartbeat;
    }

    @GetMapping("/api/corpus")
    public CorpusSnapshot corpus() { return this.corpus.snapshot(); }

    @GetMapping(value = "/api/corpus/documents/{id}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String document(@PathVariable String id) { return this.corpus.document(id).text(); }

    @PostMapping("/api/conversations/{id}/context/preview")
    public ContextReport preview(@PathVariable UUID id, @Valid @RequestBody Request request) {
        this.history.get(id.toString());
        return this.chat.preview(id.toString(), request.message(), request.mode());
    }

    @PostMapping(value = "/api/conversations/{id}/context/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> stream(@PathVariable UUID id,
            @Valid @RequestBody Request request) {
        this.history.get(id.toString());
        var events = this.chat.stream(id.toString(), request.message(), request.mode())
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
        var heartbeat = Flux.interval(this.heartbeat)
                .map(ignored -> ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(events.mergeWith(heartbeat)
                        .takeUntil(frame -> frame.data() != null && frame.data().terminal()));
    }

    public record Request(@NotBlank @Size(max = 4000) String message, ContextMode mode) {
        public Request { if (mode == null) mode = ContextMode.CHAT; }
    }
}
