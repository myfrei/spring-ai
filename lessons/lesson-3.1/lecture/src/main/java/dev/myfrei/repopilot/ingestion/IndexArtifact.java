package dev.myfrei.repopilot.ingestion;

import java.util.List;

public record IndexArtifact(int formatVersion, String fingerprint, String corpusVersion, ChunkingOptions options,
        EmbeddingIdentity embedding, List<EmbeddedChunk> chunks) {
    public IndexArtifact { chunks = List.copyOf(chunks); }
    public static String fingerprint(String corpusVersion, ChunkingOptions options, EmbeddingIdentity identity) {
        return Hashes.identity("index-artifact-v1", corpusVersion, options.identity(), identity.model(), identity.digest(),
                Integer.toString(identity.dimensions()), identity.inputFormat());
    }
}
