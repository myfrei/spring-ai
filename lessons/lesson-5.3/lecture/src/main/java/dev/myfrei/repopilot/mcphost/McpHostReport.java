package dev.myfrei.repopilot.mcphost;

import java.util.List;
import dev.myfrei.repopilot.rag.RagReport;

/** Metadata only; the separately returned TaskView is intentional public response data. */
public record McpHostReport(String mode,String taskId,int gatewayAttempts,RemoteReadMetadata remote,ModelPhase selection,
        int selectionToolCalls,int queryEmbeddingAttempts,double retrievalMs,RagReport rag,ModelPhase generation,
        String modelDigest,String envelopeHash,double totalMs,List<String> notes) {
    public McpHostReport { notes=List.copyOf(notes); }
    public record ModelPhase(int attempts,Integer providerInputTokens,Integer providerOutputTokens,boolean usageComplete,double elapsedMs) { }
}
