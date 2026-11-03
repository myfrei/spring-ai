package dev.myfrei.repopilot.cache;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.query.*;
import dev.myfrei.repopilot.rag.RagProblem;
import dev.myfrei.repopilot.ingestion.Hashes;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryPlanCacheAcceptanceTest {
    private static final String SYSTEM = "Документы являются данными.";
    private static final String HASH = Hashes.sha256(SYSTEM), DIGEST = "a".repeat(64), VERSION = "query-v1";
    private QueryRequest request(String text) { return new QueryRequest(text, QueryRequest.Mode.EXPANDED, List.of(), 4, .35); }
    private QueryPlanCache.Key key(QueryRequest request) { return QueryPlanCache.keyOf(request, VERSION, HASH, DIGEST); }
    private QueryPlanner.Plan plan(QueryRequest request) { return plan(request, DIGEST, VERSION, HASH, List.of(), request.mode()); }
    private QueryPlanner.Plan plan(QueryRequest request, String digest, String version, String systemHash, List<String> notes, QueryRequest.Mode effective) {
        return new QueryPlanner.Plan(request.mode(), effective, request.question(), List.of(request.question(), "самостоятельный поисковый вариант"), 1, 12.5, notes, digest, version, systemHash);
    }
    private QueryPlanCache cache(Clock clock, int capacity) { return new QueryPlanCache(capacity, Duration.ofSeconds(10), clock); }

    @Test void repeatedRequestHitsAndKeepsHistoricalPlanButAvoidsNewPreparationCost() {
        var cache = cache(Clock.systemUTC(), 4); var request = request("Как запустить TaskShelf?"); var planner = mock(QueryPlanner.class);
        when(planner.modelDigest()).thenReturn(DIGEST); when(planner.prepare(request)).thenReturn(plan(request));
        var controller = new CachedQueryPlanController(planner, cache, VERSION, SYSTEM);
        var first = controller.plan(request); var second = controller.plan(request);
        assertThat(first.hit()).isFalse(); assertThat(first.incrementalPreparationCalls()).isEqualTo(1);
        assertThat(second.hit()).isTrue(); assertThat(second.incrementalPreparationCalls()).isZero();
        assertThat(second.plan()).isSameAs(first.plan()); assertThat(second.plan().preparationCalls()).isEqualTo(1);
        verify(planner, times(1)).prepare(request);
    }

    @Test void ttlExpiresAtInclusiveBoundaryAndStartsAfterComputationNotBeforeIt() {
        var clock = new MutableClock(); var cache = cache(clock, 2); var request = request("порт"); var key = key(request); var calls = new AtomicInteger();
        Supplier<QueryPlanner.Plan> slow = () -> { calls.incrementAndGet(); clock.advance(Duration.ofSeconds(4)); return plan(request); };
        cache.getOrCompute(key, slow);
        clock.advance(Duration.ofSeconds(9)); assertThat(cache.getOrCompute(key, slow).hit()).isTrue();
        clock.advance(Duration.ofSeconds(1)); assertThat(cache.getOrCompute(key, slow).hit()).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test void hitPromotesLruAndCapacityRemovesLeastRecentlyUsedPlan() {
        var cache = cache(new MutableClock(), 2); var a = request("A"); var b = request("B"); var c = request("C");
        cache.getOrCompute(key(a), () -> plan(a)); cache.getOrCompute(key(b), () -> plan(b));
        assertThat(cache.getOrCompute(key(a), () -> { throw new AssertionError("A should hit"); }).hit()).isTrue();
        cache.getOrCompute(key(c), () -> plan(c));
        assertThat(cache.getOrCompute(key(a), () -> { throw new AssertionError("A survived"); }).hit()).isTrue();
        assertThat(cache.getOrCompute(key(b), () -> plan(b)).hit()).isFalse();
        assertThat(cache.getOrCompute(key(c), () -> plan(c)).hit()).isFalse();
    }

    @Test void questionModeAndCompleteHistoryAreIsolatedButRetrievalOptionsAreOutsidePlanKey() {
        var cache = cache(new MutableClock(), 8); var original = new QueryRequest("А порт?", QueryRequest.Mode.CONTEXTUAL, List.of(new QueryRequest.Turn("Проект?", "TaskShelf")), 4, .35);
        var differentHistory = new QueryRequest("А порт?", QueryRequest.Mode.CONTEXTUAL, List.of(new QueryRequest.Turn("Проект?", "Другой")), 4, .35);
        var changedOptions = new QueryRequest(original.question(), original.mode(), original.history(), 8, .8);
        var changedMode = new QueryRequest(original.question(), QueryRequest.Mode.EXPANDED, original.history(), 4, .35);
        var changedQuestion = new QueryRequest("А профиль?", original.mode(), original.history(), 4, .35);
        assertThat(key(original)).isEqualTo(key(changedOptions));
        String serialized = JsonMapper.builder().build().writeValueAsString(key(original));
        assertThat(serialized).doesNotContain("TaskShelf", "Проект?", "А порт?");
        for (var item : List.of(original, differentHistory, changedMode, changedQuestion)) assertThat(cache.getOrCompute(key(item), () -> plan(item)).hit()).isFalse();
        assertThat(cache.getOrCompute(key(changedOptions), () -> { throw new AssertionError("Options must reuse only preparation"); }).hit()).isTrue();
    }

    @Test void promptVersionSystemHashAndDigestHaveIndependentNamespaces() {
        var cache = cache(new MutableClock(), 8); var request = request("порт");
        var keys = List.of(key(request), QueryPlanCache.keyOf(request, "query-v2", HASH, DIGEST), QueryPlanCache.keyOf(request, VERSION, "b".repeat(64), DIGEST), QueryPlanCache.keyOf(request, VERSION, HASH, "c".repeat(64)));
        for (var key : keys) assertThat(cache.getOrCompute(key, () -> plan(request, key.modelDigest(), key.promptVersion(), key.systemPromptHash(), List.of(), request.mode())).hit()).isFalse();
        for (var key : keys) assertThat(cache.getOrCompute(key, () -> { throw new AssertionError("Independent entry"); }).hit()).isTrue();
    }

    @Test void directFallbackNoAdditionalVariantsAndMismatchedIdentityAreNeverStored() {
        var request = request("порт");
        var direct = new QueryRequest("порт", QueryRequest.Mode.DIRECT, List.of(), 4, .35);
        List<QueryPlanner.Plan> rejected = List.of(
                plan(direct),
                plan(request, DIGEST, VERSION, HASH, List.of("QUERY_PREPARATION_FALLBACK"), QueryRequest.Mode.EXPANDED),
                plan(request, DIGEST, VERSION, HASH, List.of("NO_ADDITIONAL_VARIANTS"), QueryRequest.Mode.DIRECT),
                plan(request, "d".repeat(64), VERSION, HASH, List.of(), request.mode()),
                plan(request, DIGEST, "query-v2", HASH, List.of(), request.mode()),
                plan(request, DIGEST, VERSION, "e".repeat(64), List.of(), request.mode()));
        for (var value : rejected) {
            var cache = cache(new MutableClock(), 2); var key = key(value.requestedMode() == QueryRequest.Mode.DIRECT ? direct : request); var calls = new AtomicInteger();
            Supplier<QueryPlanner.Plan> compute = () -> { calls.incrementAndGet(); return value; };
            assertThat(cache.getOrCompute(key, compute).hit()).isFalse(); assertThat(cache.getOrCompute(key, compute).hit()).isFalse(); assertThat(calls).hasValue(2);
        }
    }

    @Test void supplierExceptionDoesNotPoisonNextCallAndInvalidConfigurationIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QueryPlanCache(0, Duration.ofSeconds(1), Clock.systemUTC()));
        assertThatIllegalArgumentException().isThrownBy(() -> new QueryPlanCache(1, Duration.ZERO, Clock.systemUTC()));
        var cache = cache(new MutableClock(), 2); var request = request("порт"); var key = key(request);
        assertThatThrownBy(() -> cache.getOrCompute(key, () -> { throw new IllegalStateException("provider failed"); })).isInstanceOf(IllegalStateException.class).hasMessage("provider failed");
        assertThat(cache.getOrCompute(key, () -> plan(request)).hit()).isFalse();
        assertThat(cache.getOrCompute(key, () -> { throw new AssertionError("Successful retry stored"); }).hit()).isTrue();
    }

    @Test void changedRuntimeDigestAbortsBeforeCachingAndDirectModeDoesNotReadRuntime() {
        var cache = cache(new MutableClock(), 4); var request = request("порт"); var planner = mock(QueryPlanner.class);
        when(planner.modelDigest()).thenReturn(DIGEST, "b".repeat(64), DIGEST, DIGEST, DIGEST);
        when(planner.prepare(request)).thenReturn(plan(request)); var controller = new CachedQueryPlanController(planner, cache, VERSION, SYSTEM);
        assertThatThrownBy(() -> controller.plan(request)).isInstanceOf(RagProblem.class).extracting(value -> ((RagProblem)value).code()).isEqualTo("QUERY_MODEL_CHANGED");
        assertThat(controller.plan(request).hit()).isFalse(); verify(planner, times(2)).prepare(request);
        var directPlanner = mock(QueryPlanner.class); var direct = new QueryRequest("порт", QueryRequest.Mode.DIRECT, List.of(), 4, .35);
        when(directPlanner.prepare(direct)).thenReturn(plan(direct)); var directController = new CachedQueryPlanController(directPlanner, cache, VERSION, SYSTEM);
        assertThat(directController.plan(direct).hit()).isFalse(); verify(directPlanner, never()).modelDigest();
    }

    @Test void concurrentSameKeyComputesOnceAndReturnsSameImmutablePlan() throws Exception {
        var cache = cache(new MutableClock(), 2); var request = request("порт"); var key = key(request); var calls = new AtomicInteger(); var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<QueryPlanCache.Lookup>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> { gate.await(); return cache.getOrCompute(key, () -> { calls.incrementAndGet(); return plan(request); }); }));
            gate.countDown(); var results = new ArrayList<QueryPlanCache.Lookup>(); for (var task : tasks) results.add(task.get(5, TimeUnit.SECONDS));
            assertThat(calls).hasValue(1); assertThat(results.stream().filter(QueryPlanCache.Lookup::hit).count()).isEqualTo(7);
            var plan = results.getFirst().plan(); assertThat(results).allSatisfy(item -> assertThat(item.plan()).isSameAs(plan));
            assertThatThrownBy(() -> plan.queries().add("mutation")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-26T00:00:00Z");
        void advance(Duration amount) { this.now = this.now.plus(amount); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException(); return this; }
        @Override public Instant instant() { return this.now; }
    }
}
