package dev.myfrei.repopilot.rag;

import java.util.List;

public record RagSearchResult(RagManifest manifest, List<RagHit> hits) {
    public RagSearchResult { hits = List.copyOf(hits); }
}
