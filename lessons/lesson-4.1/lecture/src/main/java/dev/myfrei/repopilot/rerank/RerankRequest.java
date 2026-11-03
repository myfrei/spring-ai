package dev.myfrei.repopilot.rerank;

import dev.myfrei.repopilot.query.QueryRequest;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RerankRequest(QueryRequest query,Strategy strategy,@JsonDeserialize(using=StrictKeepDeserializer.class) Integer keep) {
    public enum Strategy { NONE, LEXICAL, LLM }
    public RerankRequest {
        if (query==null) throw new IllegalArgumentException("Query required");
        if (strategy==null) strategy=Strategy.NONE;
        if (keep==null) keep=4;
        if (keep<1||keep>4) throw new IllegalArgumentException("Keep 1..4");
    }
}
