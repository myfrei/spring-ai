package dev.myfrei.repopilot.ingestion;

public record EmbeddingIdentity(String model, String digest, int dimensions, String inputFormat) { }
