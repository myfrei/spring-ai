package dev.myfrei.repopilot.rerank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reranking")
@ConditionalOnProperty(name={"repopilot.rerank.enabled","repopilot.query.enabled","repopilot.rag.enabled"},havingValue="true")
public class RerankController {
    private final RerankPipeline pipeline; private final RerankCallService calls; private final RerankComparisonService comparisons;
    public RerankController(RerankPipeline pipeline,RerankCallService calls,RerankComparisonService comparisons) { this.pipeline=pipeline; this.calls=calls; this.comparisons=comparisons; }
    @PostMapping("/prepare") public RerankPipeline.Report prepare(@RequestBody RerankRequest request) { return pipeline.prepare(request).report(); }
    @PostMapping("/call") public RerankCallService.Reply call(@RequestBody RerankRequest request) { return calls.call(request); }
    @PostMapping("/compare") public RerankComparisonService.Comparison compare(@RequestBody RerankComparisonService.Request request) { return comparisons.compare(request); }
}
