package dev.myfrei.repopilot.rankingeval;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ranking")
public class RankingEvaluationController {
    private final RankingMetrics metrics;
    public RankingEvaluationController(RankingMetrics metrics) { this.metrics = metrics; }
    @PostMapping("/evaluate")
    public RankingScore evaluate(@RequestBody Map<String, Object> body) {
        if (!body.keySet().equals(Set.of("rankedDocumentIds", "relevanceByDocument", "k"))
                || !(body.get("rankedDocumentIds") instanceof List<?> ids)
                || !(body.get("relevanceByDocument") instanceof Map<?, ?> grades)
                || !(body.get("k") instanceof Integer cutoff) || ids.size() > 100 || grades.size() > 100) throw invalid();
        var ranking = new ArrayList<String>();
        for (Object id : ids) { if (!(id instanceof String value)) throw invalid(); ranking.add(value); }
        var relevance = new LinkedHashMap<String, Integer>();
        for (var entry : grades.entrySet()) {
            if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof Integer grade)) throw invalid();
            relevance.put(id, grade);
        }
        return this.metrics.evaluate(new RankingObservation(ranking, relevance, cutoff));
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Expected bounded ranking metadata with integer grades/K"); }
}
