package dev.myfrei.repopilot.query;
import java.util.*;
import dev.myfrei.repopilot.rag.*;
public final class ReciprocalRankFusion{
 private ReciprocalRankFusion(){}
 public record Rank(String chunkId,double fusionScore,int support,double bestCosine){}
 public record Merged(RagSearchResult retrieval,List<Rank> ranks){public Merged{ranks=List.copyOf(ranks);}}
 private static class Score{RagHit best;double fused;int support;Score(RagHit hit){best=hit;}}
 public static Merged merge(List<RagSearchResult> results,int limit){
  if(results.isEmpty()||results.size()>3||limit<1||limit>8)throw new IllegalArgumentException("Bounded results required");
  var manifest=results.getFirst().manifest();var scores=new HashMap<String,Score>();
  for(var result:results){
   if(!manifest.equals(result.manifest()))throw new RagProblem("INDEX_CHANGED","Нельзя объединять разные снимки индекса.");
   var seen=new HashSet<String>();int rank=0;
   for(var hit:result.hits()){rank++;if(!seen.add(hit.source().chunkId()))continue;
    var value=scores.computeIfAbsent(hit.source().chunkId(),id->new Score(hit));
    if(!value.best.text().equals(hit.text())||!value.best.source().documentId().equals(hit.source().documentId()))throw new RagProblem("INDEX_CORRUPT","Одинаковый chunkId относится к разным текстам.");
    value.fused+=1.0/(60+rank);value.support++;if(hit.source().score()>value.best.source().score())value.best=hit;
   }
  }
  var ranked=scores.values().stream().sorted(Comparator.<Score>comparingDouble(score->score.fused).reversed().thenComparing(score->score.best.source().chunkId())).limit(limit).toList();
  var hits=new ArrayList<RagHit>();var ranks=new ArrayList<Rank>();
  for(var value:ranked){var source=value.best.source();hits.add(new RagHit(new RagSource("S"+(hits.size()+1),source.chunkId(),source.documentId(),source.path(),source.title(),source.section(),source.startLine(),source.endLine(),source.textHash(),source.score()),value.best.text()));
   ranks.add(new Rank(source.chunkId(),value.fused,value.support,source.score()));}
  return new Merged(new RagSearchResult(manifest,hits),ranks);
 }
}
