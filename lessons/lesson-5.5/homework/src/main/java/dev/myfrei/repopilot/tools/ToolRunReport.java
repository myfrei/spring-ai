package dev.myfrei.repopilot.tools;

import java.util.List;

/** Metadata only: never store raw questions, arguments, results or generated text here. */
public record ToolRunReport(String mode,String taskId,int modelCalls,int toolCalls,int successfulToolCalls,
        boolean requiredTaskRead,Integer providerInputTokens,Integer providerOutputTokens,boolean usageComplete,
        List<Invocation> invocations,List<Round> rounds,String modelDigest,String systemPromptHash,String promptVersion,
        int inputBudget,int maxModelCalls,int maxToolCalls,double elapsedMs,List<String> notes) {
    public ToolRunReport { invocations=List.copyOf(invocations);rounds=List.copyOf(rounds);notes=List.copyOf(notes); }
    public record Invocation(int sequence,String tool,String taskId,String outcome,String code,double elapsedMs) { }
    public record Round(int number,int estimatedInputTokens,boolean completed,Integer providerInputTokens,
            Integer providerOutputTokens,String finishReason,int requestedToolCalls) { }
}
