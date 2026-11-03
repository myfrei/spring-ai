package dev.myfrei.repopilot.tools;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskView;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.EmptyUsage;

/** Created per request and never registered as a singleton bean. */
public final class ToolRun {
    public static final int MAX_MODEL_CALLS=4,MAX_TOOL_CALLS=4;
    private final String mode,taskId; private final int inputBudget; private final String promptHash;
    private final long started=System.nanoTime();
    private final List<ToolRunReport.Invocation> invocations=new ArrayList<>();
    private final List<ToolRunReport.Round> rounds=new ArrayList<>();
    private final List<String> notes=new ArrayList<>();
    private String digest=""; private TaskView observedTask;
    public ToolRun(String mode,String taskId,int inputBudget,String promptHash) { this.mode=mode;this.taskId=taskId;this.inputBudget=inputBudget;this.promptHash=promptHash; }
    public String taskId() { return taskId; }
    public void digest(String value) { this.digest=value; }
    public void note(String value) { if (!notes.contains(value)) notes.add(value); }
    public ToolsProblem problem(String code,String detail) { return new ToolsProblem(code,detail,report()); }
    public int beginTool(String name) {
        if (invocations.size()>=MAX_TOOL_CALLS) throw problem("TOOLS_CALL_LIMIT","Достигнут лимит инструментов; результат не подтверждён.");
        int sequence=invocations.size()+1;
        invocations.add(new ToolRunReport.Invocation(sequence,name,null,"STARTED","",0)); return sequence;
    }
    public void finishTool(int sequence,String id,String outcome,String code,long start) {
        var prior=invocations.get(sequence-1);
        invocations.set(sequence-1,new ToolRunReport.Invocation(sequence,prior.tool(),id,outcome,code,(System.nanoTime()-start)/1e6));
    }
    public void observedTask(TaskView task) { observedTask=task; }
    public TaskView verifiedTask() {
        return observedTask!=null&&invocations.stream().anyMatch(call->call.tool().equals("getTask")&&taskId.equals(call.taskId())&&call.outcome().equals("SUCCESS"))?observedTask:null;
    }
    public void beginRound(int estimated) {
        if (rounds.size()>=MAX_MODEL_CALLS) throw problem("TOOLS_MODEL_LIMIT","Достигнут лимит model calls; используйте детерминированный workflow.");
        if (estimated>inputBudget) throw problem("TOOLS_CONTEXT_TOO_LARGE","Полный prompt с tool schemas/results не помещается в бюджет.");
        rounds.add(new ToolRunReport.Round(rounds.size()+1,estimated,false,null,null,"",0));
    }
    public void completeRound(ChatResponse response) {
        var prior=rounds.getLast(); Integer input=null,output=null; String finish=""; int proposed=0;
        if (response!=null) {
            var usage=response.getMetadata().getUsage();
            if (usage!=null&&!(usage instanceof EmptyUsage)&&usage.getPromptTokens()!=null&&usage.getCompletionTokens()!=null
                    &&usage.getPromptTokens()>=0&&usage.getCompletionTokens()>=0) { input=usage.getPromptTokens();output=usage.getCompletionTokens(); }
            if (response.getResult()!=null) {
                String raw=response.getResult().getMetadata().getFinishReason();
                finish=raw!=null&&Set.of("stop","tool_calls","length").contains(raw)?raw:"other";
                proposed=response.getResult().getOutput().getToolCalls().size();
            }
        }
        rounds.set(rounds.size()-1,new ToolRunReport.Round(prior.number(),prior.estimatedInputTokens(),true,input,output,finish,proposed));
    }
    public ToolRunReport report() {
        boolean complete=rounds.stream().allMatch(round->round.completed()&&round.providerInputTokens()!=null&&round.providerOutputTokens()!=null);
        Integer input=complete?rounds.stream().mapToInt(ToolRunReport.Round::providerInputTokens).sum():null;
        Integer output=complete?rounds.stream().mapToInt(ToolRunReport.Round::providerOutputTokens).sum():null;
        return new ToolRunReport(mode,taskId,rounds.size(),invocations.size(),(int)invocations.stream().filter(call->call.outcome().equals("SUCCESS")).count(),
                verifiedTask()!=null,input,output,complete,invocations,rounds,digest,promptHash,"java-tools-v1",inputBudget,MAX_MODEL_CALLS,MAX_TOOL_CALLS,(System.nanoTime()-started)/1e6,notes);
    }
}
