package dev.myfrei.repopilot.rerank;

import java.util.List;
import dev.myfrei.repopilot.rag.RagHit;

public interface RerankModel {
    record Budget(int estimatedInputTokens,int inputBudget) { }
    record Reply(String json,String finishReason,Integer providerInputTokens,Integer providerOutputTokens) { }
    String digest();
    Budget budget(String original,List<RagHit> candidates);
    Reply score(String original,List<RagHit> candidates);
}
