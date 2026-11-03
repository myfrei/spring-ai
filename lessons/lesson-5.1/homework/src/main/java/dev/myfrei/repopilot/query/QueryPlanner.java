package dev.myfrei.repopilot.query;
import java.util.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.rag.RagProblem;
public class QueryPlanner{
 public record Plan(QueryRequest.Mode requestedMode,QueryRequest.Mode effectiveMode,String original,List<String> queries,
   int preparationCalls,double preparationMs,List<String> notes,String modelDigest,String promptVersion,String systemPromptHash){
  public Plan{queries=List.copyOf(queries);notes=List.copyOf(notes);}
 }
 private final QueryModel model;private final String version;private final String systemHash;
 public QueryPlanner(QueryModel model,String version,String system){if(!version.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Prompt version");this.model=model;this.version=version;this.systemHash=Hashes.sha256(system);}
 public String modelDigest(){return model.digest();}
 public Plan prepare(QueryRequest request){
  long start=System.nanoTime();var notes=new ArrayList<String>();var queries=new LinkedHashSet<String>();queries.add(request.question());int calls=0;String digest="";
  if(request.mode()==QueryRequest.Mode.DIRECT)return plan(request,queries,calls,start,notes,digest);
  try{
   digest=model.digest();String seed=request.question();
   if(!request.history().isEmpty()){
    model.validateContextualize(request.question(),request.messages());
    calls++;String rewritten=model.contextualize(request.question(),request.messages());
    if(!valid(rewritten))throw new IllegalArgumentException("Invalid rewrite");seed=rewritten.strip();queries.add(seed);
   }else if(request.mode()==QueryRequest.Mode.CONTEXTUAL)notes.add("NO_HISTORY_DIRECT");
   if(request.mode()==QueryRequest.Mode.EXPANDED){model.validateExpand(seed);calls++;var expanded=model.expand(seed);
    if(expanded==null||expanded.size()>2||expanded.stream().anyMatch(value->!valid(value)))throw new IllegalArgumentException("Invalid expansion");
    for(String query:expanded)if(queries.size()<3)queries.add(query.strip());
   }
  }catch(RagProblem error){if(error.code().equals("QUERY_MODEL_CHANGED"))throw error;fallback(request,queries,notes,error.code());}
   catch(RuntimeException error){fallback(request,queries,notes,"");}
  // Guard also runs after invalid output, budget rejection, or an invocation error.
  if(digest!=null&&!digest.isBlank()){
   try{if(!digest.equals(model.digest()))throw new RagProblem("QUERY_MODEL_CHANGED","Модель подготовки запроса изменилась. Прогон остановлен.");}
   catch(RagProblem error){if(error.code().equals("QUERY_MODEL_CHANGED"))throw error;fallback(request,queries,notes,error.code());}
   catch(RuntimeException error){fallback(request,queries,notes,"");}
  }
  return plan(request,queries,calls,start,notes,digest);
 }
 private static void fallback(QueryRequest request,Set<String> queries,List<String> notes,String reason){
  queries.clear();queries.add(request.question());
  if(!notes.contains("QUERY_PREPARATION_FALLBACK"))notes.add("QUERY_PREPARATION_FALLBACK");
  if(!reason.isBlank()&&!notes.contains(reason))notes.add(reason);
 }
 private Plan plan(QueryRequest request,Set<String> queries,int calls,long start,List<String> notes,String digest){
  var mode=queries.size()==1&&queries.iterator().next().equals(request.question())?QueryRequest.Mode.DIRECT:request.mode();
  if(mode==QueryRequest.Mode.DIRECT&&request.mode()==QueryRequest.Mode.EXPANDED&&notes.isEmpty())notes.add("NO_ADDITIONAL_VARIANTS");
  return new Plan(request.mode(),mode,request.question(),List.copyOf(queries),calls,(System.nanoTime()-start)/1e6,notes,digest,version,systemHash);
 }
 private static boolean valid(String text){return text!=null&&!text.isBlank()&&text.length()<=256&&!text.contains("\n")&&!text.contains("\r")&&!text.contains("```");}
}
