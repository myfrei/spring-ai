package dev.myfrei.repopilot.homework;

import java.util.*;
import dev.myfrei.repopilot.ingestion.*;
import org.springframework.stereotype.Component;

@Component
public class IncrementalEmbeddingPlanner {
    public record Plan(List<EmbeddedChunk> reusable, List<Chunk> missing) {
        public Plan { reusable = List.copyOf(reusable); missing = List.copyOf(missing); }
    }
    public Plan plan(List<Chunk> current, EmbeddingIdentity identity, Optional<IndexArtifact> previous) {
        // TODO HW-2.2: реализовать новое поведение из README.
        throw new UnsupportedOperationException("TODO HW-2.2");
    }
    private static String inputKey(Chunk chunk) { return Hashes.identity(chunk.title(),chunk.section(),chunk.text()); }
}
