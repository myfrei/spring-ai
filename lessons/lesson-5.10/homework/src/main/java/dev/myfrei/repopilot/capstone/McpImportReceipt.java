package dev.myfrei.repopilot.capstone;

import java.time.Instant;

public record McpImportReceipt(
    String serverName,
    String toolName,
    String protocolVersion,
    int initializeCalls,
    int listCalls,
    int toolCalls,
    String taskDigest,
    Instant importedAt) {}
