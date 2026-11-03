package dev.myfrei.repopilot.explanation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ExplanationRequest(@NotBlank @Size(max = 4000) String message, ExplanationMode mode) {
    public ExplanationRequest { if (mode == null) mode = ExplanationMode.BRIEF; }
}
