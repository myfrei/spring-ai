package dev.myfrei.repopilot.rerank;

import java.util.*;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.ingestion.*;
import dev.myfrei.repopilot.query.*;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

class RerankFixtures {
    static final String QUESTION="Порт TaskShelf? {literal}";
    static RagSearchResult found() {
        var manifest=new RagManifest("taskshelf","a".repeat(64),"b".repeat(64),new EmbeddingIdentity("fixture","c".repeat(64),3,"v1"),3);
        return new RagSearchResult(manifest,List.of(hit(1,"RAW-SECRET-CANDIDATE Java API",.95),
                hit(2,"RAW-SECRET-CANDIDATE ＪＡＶＡ architecture",.9),hit(3,"RAW-SECRET-CANDIDATE порт TaskShelf 8091 {\"id\":\"TS-42\"}",.8)));
    }
    static RagHit hit(int number,String text,double cosine) {
        return new RagHit(new RagSource("S"+number,Hashes.sha256("chunk-"+number),"runbook","RUNBOOK.md","Fixture","Section",number,number+1,Hashes.sha256(text),cosine),text);
    }
    static QueryRequest query() { return new QueryRequest(QUESTION,QueryRequest.Mode.DIRECT,List.of(),4,.35); }
    static QueryRagPipeline.Retrieved retrieved(RagSearchResult found) {
        var plan=new QueryPlanner.Plan(QueryRequest.Mode.DIRECT,QueryRequest.Mode.DIRECT,QUESTION,List.of(QUESTION),0,0,List.of(),"","assistant-v1","d".repeat(64));
        return new QueryRagPipeline.Retrieved(plan,found,List.of(),1,2.5);
    }
    static RagPlanner planner() { return new RagPlanner("Server policy",new ContextBudget(4096,256,256),new Utf8TokenEstimator()); }
    static String scores(List<RagHit> hits) {
        var rows=new ArrayList<Map<String,Object>>();
        for (int i=0;i<hits.size();i++) rows.add(Map.of("chunkId",hits.get(i).source().chunkId(),"score",(double)i/Math.max(1,hits.size()-1)));
        return JsonMapper.builder().build().writeValueAsString(Map.of("scores",rows));
    }
    static class FakeRerankModel implements RerankModel {
        int tags,calls; String afterDigest="a".repeat(64); int estimated=100,inputBudget=3072;
        String json=scores(found().hits()),finish="stop"; RuntimeException failure;
        public String digest() { tags++; return tags==1?"a".repeat(64):afterDigest; }
        public Budget budget(String original,List<RagHit> candidates) { return new Budget(estimated,inputBudget); }
        public Reply score(String original,List<RagHit> candidates) { calls++; if (failure!=null) throw failure; return new Reply(json,finish,31,17); }
    }
    static class RecordingChat implements ChatModel {
        int calls; Prompt received; String answer="Порт 8091 [S1].",finish="stop"; RuntimeException failure;
        // ChatClient merges provider options using ChatModel's own options type, just as OllamaChatModel does.
        @Override public org.springframework.ai.ollama.api.OllamaChatOptions getOptions() {
            return org.springframework.ai.ollama.api.OllamaChatOptions.builder().build();
        }
        @Override public ChatResponse call(Prompt prompt) {
            calls++; received=prompt; if (failure!=null) throw failure;
            var generation=new Generation(new AssistantMessage(answer),ChatGenerationMetadata.builder().finishReason(finish).build());
            return new ChatResponse(List.of(generation),ChatResponseMetadata.builder().usage(new DefaultUsage(19,7)).build());
        }
    }
}
