package dev.myfrei.repopilot.rerank;

import java.util.List;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.evaluation.EvaluationDataset;
import dev.myfrei.repopilot.evaluation.Experiment.Split;
import dev.myfrei.repopilot.query.*;
import dev.myfrei.repopilot.rag.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.annotation.JsonDeserialize;

public class RerankComparisonService {
    public record Request(Split split,String caseId,RerankRequest.Strategy strategy,@JsonDeserialize(using=StrictKeepDeserializer.class) Integer keep) {
        public Request {
            if (split==null||caseId==null||caseId.isBlank()||strategy==null||strategy==RerankRequest.Strategy.NONE) throw new IllegalArgumentException("Explicit split/case and LEXICAL or LLM strategy required");
            if (keep==null) keep=4;
            if (keep<1||keep>4) throw new IllegalArgumentException("Keep 1..4");
        }
    }
    public record SharedRetrieval(QueryPlanner.Plan query,List<ReciprocalRankFusion.Rank> ranks,int searches,double retrievalMs,RagManifest manifest,int candidateCount) {
        public SharedRetrieval { ranks=List.copyOf(ranks); }
    }
    public record Comparison(String chatModelDigest,String datasetVersion,Split split,String caseId,List<RerankRequest.Strategy> order,
            SharedRetrieval sharedRetrieval,RerankCallService.Reply baseline,RerankCallService.Reply reranked,double totalMs,String note) {
        public Comparison { order=List.copyOf(order); }
    }
    private final QueryRagPipeline queries; private final RerankCallService calls; private final RerankModel model;
    private final RagIndexPublisher index; private final CorpusCatalog corpus; private final ObjectProvider<EvaluationDataset> datasets;
    public RerankComparisonService(QueryRagPipeline queries,RerankCallService calls,RerankModel model,RagIndexPublisher index,CorpusCatalog corpus,ObjectProvider<EvaluationDataset> datasets) {
        this.queries=queries; this.calls=calls; this.model=model; this.index=index; this.corpus=corpus; this.datasets=datasets;
    }
    public Comparison compare(Request request) {
        long start=System.nanoTime(); var dataset=datasets.getIfAvailable();
        if (dataset==null) throw new RagProblem("EVALUATION_DISABLED","Для сравнения нужен набор кейсов.");
        var entry=dataset.cases(request.split()).stream().filter(item->item.id().equals(request.caseId())).findFirst().orElseThrow(()->new IllegalArgumentException("Case outside explicit split"));
        String digest=model.digest();
        var query=new QueryRequest(entry.question(),QueryRequest.Mode.DIRECT,List.of(),4,.35);
        var retrieved=queries.retrieve(query); var expected=retrieved.retrieval().manifest();
        guard(expected,digest);
        var baseline=calls.callShared(new RerankRequest(query,RerankRequest.Strategy.NONE,request.keep()),retrieved);
        guard(expected,digest);
        var ranked=calls.callShared(new RerankRequest(query,request.strategy(),request.keep()),retrieved);
        guard(expected,digest);
        var shared=new SharedRetrieval(retrieved.query(),retrieved.ranks(),retrieved.searches(),retrieved.retrievalMs(),expected,retrieved.retrieval().hits().size());
        return new Comparison(digest,dataset.version(),request.split(),entry.id(),List.of(RerankRequest.Strategy.NONE,request.strategy()),shared,baseline,ranked,
                (System.nanoTime()-start)/1e6,"Один общий retrieval snapshot; фиксированный порядок final calls. Это наблюдение, не статистическая оценка улучшения. Shared retrieval учитывается один раз сверху.");
    }
    private void guard(RagManifest expected,String digest) {
        if (!digest.equals(model.digest())) throw new RagProblem("RERANK_MODEL_CHANGED","Chat-модель изменилась между сравниваемыми попытками.");
        var active=index.manifest().orElseThrow(()->new RagProblem("INDEX_CHANGED","Активный индекс исчез во время сравнения."));
        if (!expected.equals(active)||!expected.corpusVersion().equals(corpus.snapshot().version())) throw new RagProblem("INDEX_CHANGED","Индекс или корпус изменились во время сравнения.");
    }
}
