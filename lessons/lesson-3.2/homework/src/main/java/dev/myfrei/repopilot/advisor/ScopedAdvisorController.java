package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.RagRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "repopilot.rag.enabled", havingValue = "true")
public class ScopedAdvisorController {
    private final AdvisorCallService service;
    public ScopedAdvisorController(AdvisorCallService service) { this.service = service; }
    @PostMapping("/api/advisors/scoped/call") public AdvisorCallService.Reply call(@Valid @RequestBody Request request) {
        return this.service.call(new RagRequest(request.message(), request.topK(), request.threshold()), found -> DocumentScope.select(request.profile(), found));
    }
    public record Request(@NotBlank @Size(max=4000) String message, DocumentScope.Profile profile, Integer topK, Double threshold) {
        public Request { if (profile == null) throw new IllegalArgumentException("Select a server-owned profile"); }
    }
}
