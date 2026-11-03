package dev.myfrei.repopilot.auditexport;

/** json is the exact UTF-8 download body, not a JSON string embedded in a second response. */
public record PortableAuditPacket(String json, String sha256, int utf8Bytes) {}
