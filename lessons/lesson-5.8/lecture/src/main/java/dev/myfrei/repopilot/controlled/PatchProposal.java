package dev.myfrei.repopilot.controlled;

import java.time.Instant;
import java.util.UUID;

/** Public immutable view. Prepared full-file bytes remain private to the service. */
public record PatchProposal(
    UUID id,
    UUID runId,
    UUID conversationId,
    String mode,
    String path,
    String baseHash,
    String resultHash,
    String operationHash,
    String status,
    String diff,
    Instant createdAt,
    Instant expiresAt) {}
