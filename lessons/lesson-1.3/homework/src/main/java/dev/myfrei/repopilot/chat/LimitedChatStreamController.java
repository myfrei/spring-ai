package dev.myfrei.repopilot.chat;
import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
@RestController
public class LimitedChatStreamController {
    private final ChatStreamService model;
    private final OutputQuota quota;
    private final Duration heartbeat;
    public LimitedChatStreamController(ChatStreamService model, OutputQuota quota, @Value("${repopilot.stream.heartbeat}") Duration heartbeat) { this.model=model;this.quota=quota;this.heartbeat=heartbeat; }
    @PostMapping(value="/api/chat/limited-stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> stream(@Valid @RequestBody Request request) {
        var events=this.quota.apply(this.model.stream(request.message()),request.maxCodePoints()).map(event->ServerSentEvent.builder(event).event(event.type()).build());
        var beats=Flux.interval(this.heartbeat).map(ignored->ServerSentEvent.<ChatStreamEvent>builder().comment("keepalive").build());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
          .body(events.mergeWith(beats).takeUntil(frame->frame.data()!=null && frame.data().terminal()));
    }
    public record Request(@NotBlank @Size(max=4000) String message,@Min(1) @Max(6000) Integer maxCodePoints) { public Request { if(maxCodePoints==null)maxCodePoints=1000; } }
}
