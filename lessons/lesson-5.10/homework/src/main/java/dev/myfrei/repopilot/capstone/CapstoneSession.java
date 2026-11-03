package dev.myfrei.repopilot.capstone;

import java.time.Instant;
import java.util.UUID;

public record CapstoneSession(
    UUID id,
    UUID conversationId,
    StockRoomTask task,
    McpImportReceipt importReceipt,
    Instant createdAt) {}
