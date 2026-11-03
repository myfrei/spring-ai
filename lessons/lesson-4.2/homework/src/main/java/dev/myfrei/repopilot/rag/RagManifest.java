package dev.myfrei.repopilot.rag;

import dev.myfrei.repopilot.ingestion.EmbeddingIdentity;

public record RagManifest(String workspace, String fingerprint, String corpusVersion, EmbeddingIdentity embedding, int chunkCount) { }
