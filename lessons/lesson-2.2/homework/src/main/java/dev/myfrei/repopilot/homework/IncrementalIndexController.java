package dev.myfrei.repopilot.homework;

import dev.myfrei.repopilot.ingestion.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class IncrementalIndexController {
    private final IngestionService ingestion; private final IncrementalEmbeddingPlanner planner;
    public IncrementalIndexController(IngestionService ingestion,IncrementalEmbeddingPlanner planner) { this.ingestion=ingestion;this.planner=planner; }
    @PostMapping("/api/ingestion/incremental/index") public IngestionService.IncrementalResult index(@RequestBody ChunkingOptions options) { return this.ingestion.incrementalIndex(options,this.planner); }
}
