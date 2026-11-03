package dev.myfrei.repopilot.query;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import dev.myfrei.repopilot.evaluation.EvaluationDataset;
import dev.myfrei.repopilot.evaluation.Experiment.Split;
import dev.myfrei.repopilot.rag.RagProblem;
@RestController
@RequestMapping("/api/queries")
@ConditionalOnProperty(name={"repopilot.query.enabled","repopilot.rag.enabled"},havingValue="true")
public class QueryController{
 private final QueryPlanner planner;private final QueryRagPipeline pipeline;private final QueryCallService calls;private final ObjectProvider<EvaluationDataset> datasets;
 public QueryController(QueryPlanner planner,QueryRagPipeline pipeline,QueryCallService calls,ObjectProvider<EvaluationDataset> datasets){this.planner=planner;this.pipeline=pipeline;this.calls=calls;this.datasets=datasets;}
 @PostMapping("/plan")public QueryPlanner.Plan plan(@RequestBody QueryRequest request){return planner.prepare(request);}
 @PostMapping("/prepare")public QueryRagPipeline.Report prepare(@RequestBody QueryRequest request){return pipeline.prepare(request).report();}
 @PostMapping("/call")public QueryCallService.Reply call(@RequestBody QueryRequest request){return calls.call(request);}
 public record CompareRequest(Split split,String caseId){public CompareRequest{if(split==null||caseId==null)throw new IllegalArgumentException("Explicit split/case required");}}
 @PostMapping("/compare")public Object compare(@RequestBody CompareRequest request){
  var dataset=datasets.getIfAvailable();if(dataset==null)throw new RagProblem("EVALUATION_DISABLED","Для сравнения нужен набор кейсов.");
  var entry=dataset.cases(request.split()).stream().filter(item->item.id().equals(request.caseId())).findFirst().orElseThrow(()->new IllegalArgumentException("Case outside split"));
  String digest=planner.modelDigest();
  var direct=calls.call(new QueryRequest(entry.question(),QueryRequest.Mode.DIRECT,List.of(),4,.35));
  var expanded=calls.call(new QueryRequest(entry.question(),QueryRequest.Mode.EXPANDED,List.of(),4,.35));
  if (!digest.equals(planner.modelDigest())) throw new RagProblem("QUERY_MODEL_CHANGED","Chat-модель изменилась между сравниваемыми попытками.");
  if (!direct.preparation().rag().indexFingerprint().equals(expanded.preparation().rag().indexFingerprint()))
   throw new RagProblem("INDEX_CHANGED","Сравниваемые попытки использовали разные индексы.");
  return Map.of("chatModelDigest",digest,"datasetVersion",dataset.version(),"split",request.split(),"caseId",entry.id(),"order",List.of("DIRECT","EXPANDED"),"direct",direct,"expanded",expanded,
   "note","Одна пара с фиксированным порядком — наблюдение, не доказательство улучшения; качество и задержку проверять вместе.");
 }
}
