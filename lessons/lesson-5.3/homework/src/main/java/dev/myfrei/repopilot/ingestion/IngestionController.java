package dev.myfrei.repopilot.ingestion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {
    private final IngestionService ingestion;
    public IngestionController(IngestionService ingestion) { this.ingestion = ingestion; }
    @GetMapping
    public IngestionService.Status status() { return this.ingestion.status(); }
    @PostMapping("/preview")
    public IngestionService.Preview preview(@RequestBody ChunkingOptions options) { return this.ingestion.preview(options); }
    @PostMapping("/index")
    public IngestionService.Result index(@RequestBody ChunkingOptions options) { return this.ingestion.index(options); }
}
