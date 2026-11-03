package dev.myfrei.repopilot.homework;

import java.util.UUID;
import dev.myfrei.repopilot.chat.ChatStreamEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
@RestController
@ConditionalOnProperty(name="repopilot.rag.enabled",havingValue="true")
public class DiverseRagController {
    private final DiverseRagService service;
    public DiverseRagController(DiverseRagService service) { this.service=service; }
    @PostMapping("/api/rag/diverse/search") public DiverseRagService.Preview search(@Valid @RequestBody Request request) { return this.service.search(request); }
    @PostMapping(value="/api/conversations/{id}/rag/diverse/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@PathVariable UUID id,@Valid @RequestBody Request request) { return this.service.stream(id.toString(),request).map(event->ServerSentEvent.builder(event).event(event.type()).build()); }
    public record Request(@NotBlank @Size(max=4000) String message,Integer maxPerDocument,Integer maxTotal,Double overlapThreshold,Double threshold) {
        public Request { if(maxPerDocument==null)maxPerDocument=2;if(maxTotal==null)maxTotal=4;if(overlapThreshold==null)overlapThreshold=.5;if(threshold==null)threshold=.35;if(maxPerDocument<1 || maxPerDocument>8 || maxTotal<1 || maxTotal>8 || !Double.isFinite(overlapThreshold) || overlapThreshold<0 || overlapThreshold>1 || !Double.isFinite(threshold) || threshold<0 || threshold>1)throw new IllegalArgumentException("Invalid diversity options"); }
    }
}
