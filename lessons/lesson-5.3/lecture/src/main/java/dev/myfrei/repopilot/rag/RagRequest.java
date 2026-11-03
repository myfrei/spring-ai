package dev.myfrei.repopilot.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagRequest(@NotBlank @Size(max = 4000) String message, Integer topK, Double threshold) {
    public RagRequest {
        if (topK == null) topK = 4;
        if (threshold == null) threshold = 0.35;
        if (topK < 1 || topK > 8 || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Invalid retrieval options");
        }
    }
}
