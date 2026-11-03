package dev.myfrei.repopilot.conversation;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import dev.myfrei.repopilot.chat.ChatRequest;
import dev.myfrei.repopilot.chat.ChatStreamEvent;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final SqliteHistory history;
    private final ConversationChatService chat;
    private final Duration heartbeat;

    public ConversationController(SqliteHistory history, ConversationChatService chat,
            @Value("${repopilot.stream.heartbeat}") Duration heartbeat) {
        this.history = history; this.chat = chat; this.heartbeat = heartbeat;
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@Valid @RequestBody CreateConversation request) {
        Conversation result = this.history.create(request.title());
        return ResponseEntity.created(URI.create("/api/conversations/" + result.id())).body(result);
    }

    @GetMapping
    public List<Conversation> list() { return this.history.list(); }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable UUID id) {
        return new Detail(this.history.get(id.toString()), this.history.turns(id.toString()));
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> stream(@PathVariable UUID id,
            @Valid @RequestBody ChatRequest request) {
        this.history.get(id.toString()); // 404 до отправки SSE-заголовков.
        var events = this.chat.stream(id.toString(), request.message())
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
        var keepAlive = Flux.interval(this.heartbeat)
                .map(ignored -> ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(events.mergeWith(keepAlive)
                        .takeUntil(frame -> frame.data() != null && frame.data().terminal()));
    }

    public record CreateConversation(@NotBlank @Size(max = 80) String title) { }
    public record Detail(Conversation conversation, List<Turn> turns) { }
}
