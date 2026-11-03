package dev.myfrei.repopilot.verification;

import java.time.Instant;
import java.util.UUID;

public record VerificationReport(
    UUID id,
    UUID requestId,
    UUID conversationId,
    String command,
    String inputRevision,
    String imageId,
    String status,
    Integer exitCode,
    String stdout,
    String stderr,
    boolean outputTruncated,
    boolean inputsStillCurrent,
    Instant startedAt,
    Instant finishedAt,
    double elapsedMs,
    String reason) {}
