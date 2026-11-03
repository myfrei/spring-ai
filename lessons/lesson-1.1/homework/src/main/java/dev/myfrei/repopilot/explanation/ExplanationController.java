package dev.myfrei.repopilot.explanation;
import jakarta.validation.Valid;
import dev.myfrei.repopilot.chat.ChatResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public class ExplanationController {
    private final ExplanationService service;
    public ExplanationController(ExplanationService service) { this.service = service; }
    @PostMapping("/api/explanations") public ChatResponse explain(@Valid @RequestBody ExplanationRequest request) { return new ChatResponse(this.service.explain(request)); }
}
