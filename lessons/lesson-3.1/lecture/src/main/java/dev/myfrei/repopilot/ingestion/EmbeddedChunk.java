package dev.myfrei.repopilot.ingestion;

import java.util.List;

public record EmbeddedChunk(Chunk chunk, List<Float> vector) {
    public EmbeddedChunk { vector = List.copyOf(vector); }
}
