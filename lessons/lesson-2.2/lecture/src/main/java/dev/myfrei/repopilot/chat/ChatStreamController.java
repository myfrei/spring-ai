package dev.myfrei.repopilot.chat;

import java.time.Duration;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatStreamController {

    private final ChatStreamService service;
    private final Duration heartbeat;

    public ChatStreamController(ChatStreamService service,
            @Value("${repopilot.stream.heartbeat}") Duration heartbeat) {
        this.service = service;
        this.heartbeat = heartbeat;
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> stream(@Valid @RequestBody ChatRequest request) {
        Flux<ServerSentEvent<ChatStreamEvent>> events = this.service.stream(request.message())
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
        Flux<ServerSentEvent<ChatStreamEvent>> keepAlive = Flux.interval(this.heartbeat)
                .map(ignored -> ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(events.mergeWith(keepAlive)
                        .takeUntil(frame -> frame.data() != null && frame.data().terminal()));
    }
}
