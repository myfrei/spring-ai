package dev.myfrei.repopilot.rerank;

import java.util.List;
import dev.myfrei.repopilot.query.*;
import dev.myfrei.repopilot.rag.*;

public class RerankPipeline {
    public record Report(QueryPlanner.Plan query,List<ReciprocalRankFusion.Rank> ranks,int searches,double retrievalMs,
            boolean sharedRetrieval,RerankReport reranking,RagReport rag) {
        public Report { ranks=List.copyOf(ranks); }
    }
    public record Prepared(RagPlan rag,Report report) { }
    private final QueryRagPipeline queries; private final RerankService ranking; private final RagPlanner planner;
    public RerankPipeline(QueryRagPipeline queries,RerankService ranking,RagPlanner planner) { this.queries=queries; this.ranking=ranking; this.planner=planner; }
    public Prepared prepare(RerankRequest request) { return using(request,queries.retrieve(request.query()),false); }
    public Prepared prepareShared(RerankRequest request,QueryRagPipeline.Retrieved retrieved) { return using(request,retrieved,true); }
    private Prepared using(RerankRequest request,QueryRagPipeline.Retrieved retrieved,boolean shared) {
        if (!retrieved.query().original().equals(request.query().question())) throw new IllegalArgumentException("Snapshot belongs to another question");
        var selected=ranking.rank(request.query().question(),retrieved.retrieval(),request.strategy(),request.keep());
        var rag=planner.plan(request.query().question(),request.query().messages(),selected.retrieval());
        return new Prepared(rag,new Report(retrieved.query(),retrieved.ranks(),shared?0:retrieved.searches(),shared?0:retrieved.retrievalMs(),shared,selected.report(),rag.report()));
    }
}
