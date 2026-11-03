package dev.myfrei.repopilot.cache;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import dev.myfrei.repopilot.query.QueryPlanner;
import dev.myfrei.repopilot.query.QueryRequest;
import dev.myfrei.repopilot.ingestion.Hashes;
import tools.jackson.databind.json.JsonMapper;

/** A bounded local cache of query-preparation plans, never retrieval results or answers. */
public class QueryPlanCache {
    public record Key(String requestHash, QueryRequest.Mode mode, String promptVersion, String systemPromptHash, String modelDigest) {
        public Key {
            if (mode == null || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || promptVersion == null || !promptVersion.matches("[a-z0-9-]{1,40}")
                    || systemPromptHash == null || !systemPromptHash.matches("[0-9a-f]{64}")
                    || modelDigest == null || !modelDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cache key");
        }
    }
    public record Lookup(boolean hit, QueryPlanner.Plan plan) { }
    private record Entry(QueryPlanner.Plan plan, Instant expiresAt) { }
    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);

    public QueryPlanCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1 || ttl == null || ttl.isZero() || ttl.isNegative() || clock == null) throw new IllegalArgumentException("Positive capacity/TTL and clock required");
        this.capacity = capacity; this.ttl = ttl; this.clock = clock;
    }

    public synchronized Lookup getOrCompute(Key key, Supplier<QueryPlanner.Plan> supplier) {
        // TODO HW-3.3: TTL/LRU, supplier exceptions, cacheability and bounded storage.
        throw new UnsupportedOperationException("HW-3.3: cache prepared query plans");
    }

    public static Key keyOf(QueryRequest request, String version, String systemHash, String digest) {
        Objects.requireNonNull(request);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("mode", request.mode()); fields.put("question", request.question()); fields.put("history", request.history());
        String fingerprint = Hashes.sha256(JsonMapper.builder().build().writeValueAsString(fields));
        return new Key(fingerprint, request.mode(), version, systemHash, digest);
    }

    private static boolean cacheable(Key key, QueryPlanner.Plan plan) {
        return key.mode() != QueryRequest.Mode.DIRECT && plan.requestedMode() == key.mode()
                && plan.effectiveMode() == key.mode() && plan.preparationCalls() > 0
                && !plan.notes().contains("QUERY_PREPARATION_FALLBACK")
                && key.promptVersion().equals(plan.promptVersion()) && key.systemPromptHash().equals(plan.systemPromptHash())
                && key.modelDigest().equals(plan.modelDigest());
    }
}
