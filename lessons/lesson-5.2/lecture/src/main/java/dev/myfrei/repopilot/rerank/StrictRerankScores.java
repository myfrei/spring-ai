package dev.myfrei.repopilot.rerank;

import java.util.*;
import dev.myfrei.repopilot.rag.RagProblem;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class StrictRerankScores {
    private StrictRerankScores() { }
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static Map<String,Double> parse(String json,Set<String> expected) {
        try {
            if (json==null||json.isBlank()||json.length()>16384||expected.isEmpty()||expected.size()>8) throw invalid();
            Object parsed=JSON.readValue(json,Object.class);
            if (!(parsed instanceof Map<?,?> root)||root.size()!=1||!(root.get("scores") instanceof List<?> rows)||rows.size()!=expected.size()) throw invalid();
            var scores=new LinkedHashMap<String,Double>();
            for (Object item:rows) {
                if (!(item instanceof Map<?,?> row)||!row.keySet().equals(Set.of("chunkId","score"))
                        ||!(row.get("chunkId") instanceof String id)||!expected.contains(id)||!(row.get("score") instanceof Number number)) throw invalid();
                double score=number.doubleValue();
                if (!Double.isFinite(score)||score<0||score>1||scores.putIfAbsent(id,score==0?0.0:score)!=null) throw invalid();
            }
            if (!scores.keySet().equals(expected)) throw invalid();
            return Map.copyOf(scores);
        } catch (RagProblem error) { throw error; }
        catch (RuntimeException error) { throw invalid(); }
    }
    private static RagProblem invalid() { return new RagProblem("INVALID_RERANK_OUTPUT","Список оценок не прошёл строгую проверку."); }
}
