package dev.myfrei.repopilot.query;
import java.util.*;
import dev.myfrei.repopilot.rag.*;
public class QueryRagPipeline{
 public record Report(RagReport rag, QueryPlanner.Plan query, List<ReciprocalRankFusion.Rank> ranks, int searches, double retrievalMs) {
  public Report { ranks=List.copyOf(ranks); }
 }
 public record Prepared(RagPlan rag,QueryPlanner.Plan query,List<ReciprocalRankFusion.Rank> ranks,int searches,double retrievalMs){
  public Prepared{ranks=List.copyOf(ranks);}
  public Report report(){return new Report(rag.report(),query,ranks,searches,retrievalMs);}
 }
 public record Retrieved(QueryPlanner.Plan query,RagSearchResult retrieval,List<ReciprocalRankFusion.Rank> ranks,int searches,double retrievalMs){
  public Retrieved{ranks=List.copyOf(ranks);}
 }
 private final QueryPlanner queries;private final RagSearcher searcher;private final RagPlanner planner;
 public QueryRagPipeline(QueryPlanner queries,RagSearcher searcher,RagPlanner planner){this.queries=queries;this.searcher=searcher;this.planner=planner;}
 public Prepared prepare(QueryRequest request){
  var retrieved=retrieve(request);long start=System.nanoTime();
  var rag=planner.plan(request.question(),request.messages(),retrieved.retrieval());
  return new Prepared(rag,retrieved.query(),retrieved.ranks(),retrieved.searches(),retrieved.retrievalMs()+(System.nanoTime()-start)/1e6);
 }
 public Retrieved retrieve(QueryRequest request){
  var query=queries.prepare(request);long start=System.nanoTime();var found=new ArrayList<RagSearchResult>();
  for(String text:query.queries())found.add(searcher.search(new RagRequest(text,request.topK(),request.threshold())));
  var merged=ReciprocalRankFusion.merge(found,8);
  return new Retrieved(query,merged.retrieval(),merged.ranks(),found.size(),(System.nanoTime()-start)/1e6);
 }
}
