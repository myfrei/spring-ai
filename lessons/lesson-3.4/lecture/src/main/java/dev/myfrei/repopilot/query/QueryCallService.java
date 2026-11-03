package dev.myfrei.repopilot.query;
import dev.myfrei.repopilot.advisor.SnapshotAdvisorFactory;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.ChatClient;
public class QueryCallService{
 public record Reply(String answer,String outcome,QueryRagPipeline.Report preparation,double totalMs,Integer providerInputTokens,Integer providerOutputTokens){}
 private final QueryRagPipeline pipeline;private final ChatClient client;
 public QueryCallService(QueryRagPipeline pipeline,ChatClient.Builder builder){this.pipeline=pipeline;this.client=builder.build();}
 public Reply call(QueryRequest request){
  long start=System.nanoTime();var prepared=pipeline.prepare(request);
  if(prepared.rag().references().isEmpty())return new Reply(RagChatService.NO_EVIDENCE,"NO_EVIDENCE",prepared.report(),(System.nanoTime()-start)/1e6,null,null);
  var response=client.prompt().messages(prepared.rag().baseMessages()).advisors(SnapshotAdvisorFactory.create(prepared.rag())).call().chatResponse();
  if(response==null||response.getResult()==null||!"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason()))throw new RagProblem("UPSTREAM_INCOMPLETE","Генерация не подтвердила завершение.");
  String answer=response.getResult().getOutput().getText();if(answer==null||answer.isBlank())throw new RagProblem("EMPTY_RESPONSE","Модель вернула пустой ответ.");
  var usage=response.getMetadata().getUsage();
  return new Reply(answer,"COMPLETED",prepared.report(),(System.nanoTime()-start)/1e6,usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens());
 }
}
