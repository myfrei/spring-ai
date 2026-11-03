package dev.myfrei.repopilot.verification;

import java.time.Instant;
import java.util.UUID;

public record VerificationRequest(
    UUID id,
    UUID conversationId,
    String command,
    String inputRevision,
    String imageId,
    String status,
    Instant createdAt,
    Instant expiresAt) {}
