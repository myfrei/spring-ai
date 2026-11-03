package dev.myfrei.repopilot.workspace;

/** Server-owned record of complete lines actually delivered to one agent run. */
public record ReadReceipt(
    String runId, String path, String fullFileHash, int startLine, int endLine) {}
