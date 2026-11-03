package dev.myfrei.repopilot.mcphost;
import java.util.*;
import dev.myfrei.repopilot.advisor.*;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.tasks.*;
import dev.myfrei.repopilot.tools.ToolModelIdentity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
class HostFixtures {
 static final String DIGEST="a".repeat(64),QUESTION="PRIVATE-QUESTION: как запустить {literal}?";
 static McpHostRequest request(){return new McpHostRequest("TASK-101",QUESTION);}
 static ChatResponse response(String text,String finish,List<AssistantMessage.ToolCall> tools){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).toolCalls(tools).build(),ChatGenerationMetadata.builder().finishReason(finish).build())),ChatResponseMetadata.builder().usage(new DefaultUsage(8,3)).build());}
 static AssistantMessage.ToolCall tool(String name,String args,int id){return new AssistantMessage.ToolCall("call-"+id,"function",name,args);}
 static ChatResponse select(){return response("","stop",List.of(tool(RemoteReadMetadata.TOOL,"{\"taskId\":\"TASK-101\"}",1)));}
 static ChatResponse answer(){return response("Ответ на основе источника [S1].","stop",List.of());}
 static class Model implements ChatModel{
  int calls;List<Prompt> prompts=new ArrayList<>();List<ChatResponse>script=new ArrayList<>();RuntimeException failure;
  @Override public OllamaChatOptions getOptions(){return OllamaChatOptions.builder().model("fixture").temperature(.99).numCtx(9999).numPredict(999).build();}
  @Override public ChatResponse call(Prompt prompt){prompts.add(prompt);calls++;if(failure!=null)throw failure;return script.get(Math.min(calls-1,script.size()-1));}
 }
 static class Identity implements ToolModelIdentity{int calls;List<String>sequence=new ArrayList<>();public String digest(){calls++;return sequence.isEmpty()?DIGEST:sequence.get(Math.min(calls-1,sequence.size()-1));}}
 static class Gateway implements RemoteTaskGateway{int calls;GatewayException failure;public ValidatedTaskRead readTask(String id){calls++;if(failure!=null)throw failure;return new ValidatedTaskRead(new TaskCatalog().findTask(id).orElseThrow().toView(),new RemoteReadMetadata("taskshelf",RemoteReadMetadata.TOOL,"2025-11-25",1,1,1,2));}}
 static class Preparation extends RagPreparationService{
  int calls;boolean empty;RagProblem failure;String question;
  Preparation(){super(null,AdvisorFixtures.planner());}
  @Override public RagPlan prepareObserved(RagRequest request,List<Message>history,Runnable observer){calls++;question=request.message();observer.run();if(failure!=null)throw failure;var found=AdvisorFixtures.retrieval();if(empty)found=new RagSearchResult(found.manifest(),List.of());return AdvisorFixtures.planner().plan(question,history,found);}
 }
 static McpHostEngine engine(Gateway gateway,Preparation preparation,Model model,Identity identity,int window){return new McpHostEngine(gateway,preparation,ChatClient.builder(model),identity,"fixture",window);}
}
