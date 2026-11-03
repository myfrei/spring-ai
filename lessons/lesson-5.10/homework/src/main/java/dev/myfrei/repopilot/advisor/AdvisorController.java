package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.RagRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name = "repopilot.rag.enabled", havingValue = "true")
@RequestMapping("/api/advisors")
public class AdvisorController {
    private final AdvisorCallService service;
    public AdvisorController(AdvisorCallService service) { this.service = service; }
    @PostMapping("/call") public AdvisorCallService.Reply call(@Valid @RequestBody RagRequest request) { return this.service.call(request); }
}
