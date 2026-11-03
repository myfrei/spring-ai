package dev.myfrei.repopilot.cache;

import dev.myfrei.repopilot.query.*;
import dev.myfrei.repopilot.rag.RagProblem;
import dev.myfrei.repopilot.ingestion.Hashes;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;

@RestController
@ConditionalOnProperty(name={"repopilot.query.enabled", "repopilot.rag.enabled"}, havingValue="true")
@RequestMapping("/api/queries/cached")
public class CachedQueryPlanController {
    public record Reply(boolean hit, QueryPlanner.Plan plan, int incrementalPreparationCalls) { }
    private final QueryPlanner planner;
    private final QueryPlanCache cache;
    private final String version;
    private final String systemHash;
    public CachedQueryPlanController(QueryPlanner planner, QueryPlanCache cache,
            @Value("${repopilot.query.prompt-version}") String version,
            @Value("${repopilot.system-prompt}") String system) {
        this.planner = planner; this.cache = cache; this.version = version; this.systemHash = Hashes.sha256(system);
    }
    @PostMapping("/plan")
    public Reply plan(@RequestBody QueryRequest request) {
        if (request.mode() == QueryRequest.Mode.DIRECT) {
            var plan = this.planner.prepare(request); return new Reply(false, plan, plan.preparationCalls());
        }
        String digest = this.planner.modelDigest();
        var key = QueryPlanCache.keyOf(request, this.version, this.systemHash, digest);
        var found = this.cache.getOrCompute(key, () -> {
            var plan = this.planner.prepare(request);
            requireDigest(digest);
            if (!plan.modelDigest().isEmpty() && !digest.equals(plan.modelDigest())) throw changed();
            return plan;
        });
        requireDigest(digest);
        return new Reply(found.hit(), found.plan(), found.hit() ? 0 : found.plan().preparationCalls());
    }
    private void requireDigest(String expected) { if (!expected.equals(this.planner.modelDigest())) throw changed(); }
    private RagProblem changed() { return new RagProblem("QUERY_MODEL_CHANGED", "Модель подготовки запроса изменилась во время обращения к cache."); }
}
