package dev.myfrei.repopilot.rerank;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import dev.myfrei.repopilot.rag.*;
import static dev.myfrei.repopilot.rerank.RerankRequest.Strategy.*;

public class RerankService {
    public record Decision(RagSearchResult retrieval,RerankReport report) { }
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}\\p{N}]+");
    private final RerankModel model;
    private final String promptVersion;
    public RerankService(RerankModel model,String promptVersion) {
        if (promptVersion==null||!promptVersion.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Prompt version required");
        this.model=model; this.promptVersion=promptVersion;
    }
    public Decision rank(String original,RagSearchResult found,RerankRequest.Strategy strategy,int keep) {
        if (original==null||original.isBlank()||original.length()>4000||strategy==null||keep<1||keep>4||found.hits().size()>8)
            throw new IllegalArgumentException("Bounded rerank input required");
        long start=System.nanoTime(); var hits=found.hits(); var originalRanks=new HashMap<String,Integer>();
        for (int i=0;i<hits.size();i++) if (originalRanks.put(hits.get(i).source().chunkId(),i+1)!=null) throw new IllegalArgumentException("Unique chunk IDs required");
        var effective=strategy; Map<String,Double> scores=null; int attempts=0;
        String digest=""; var notes=new ArrayList<String>(); RerankModel.Budget budget=null; RerankModel.Reply reply=null;
        if (hits.isEmpty()) { effective=NONE; notes.add("NO_CANDIDATES"); }
        else if (strategy==LEXICAL) {
            scores=new HashMap<>(); var queryTokens=tokens(original);
            for (var hit:hits) {
                var words=tokens(hit.text()); long overlap=queryTokens.stream().filter(words::contains).count();
                scores.put(hit.source().chunkId(),queryTokens.isEmpty()?0.0:(double)overlap/queryTokens.size());
            }
        } else if (strategy==LLM) {
            try {
                digest=model.digest(); budget=model.budget(original,hits);
                if (budget.estimatedInputTokens()>budget.inputBudget()) throw new RagProblem("RERANK_CONTEXT_TOO_LARGE","Listwise prompt не помещается в бюджет.");
                attempts++; reply=model.score(original,hits);
                if (reply==null||!"stop".equalsIgnoreCase(reply.finishReason())) throw new RagProblem("RERANK_INCOMPLETE","Модель не подтвердила завершение списка.");
                scores=StrictRerankScores.parse(reply.json(),originalRanks.keySet());
            } catch (RuntimeException error) {
                if (error instanceof RagProblem problem&&problem.code().equals("RERANK_MODEL_CHANGED")) throw error;
                effective=NONE; scores=null; fallback(notes,error);
            }
            // Execute the identity guard even after malformed output, budget rejection, or transport failure.
            if (digest!=null&&!digest.isBlank()) {
                try {
                    if (!digest.equals(model.digest())) throw new RagProblem("RERANK_MODEL_CHANGED","Модель reranking изменилась во время попытки.");
                } catch (RuntimeException error) {
                    if (error instanceof RagProblem problem&&problem.code().equals("RERANK_MODEL_CHANGED")) throw error;
                    effective=NONE; scores=null; fallback(notes,error);
                }
            }
        }
        var ordered=new ArrayList<>(hits);
        if (scores!=null) {
            var finalScores=scores;
            ordered.sort(Comparator.<RagHit>comparingDouble(hit->finalScores.get(hit.source().chunkId())).reversed()
                    .thenComparingInt(hit->originalRanks.get(hit.source().chunkId())));
        }
        var selected=new ArrayList<RagHit>(); var ranking=new ArrayList<RerankReport.Rank>();
        for (int i=0;i<ordered.size();i++) {
            var hit=ordered.get(i); var source=hit.source(); boolean kept=i<keep;
            ranking.add(new RerankReport.Rank(source.chunkId(),originalRanks.get(source.chunkId()),i+1,source.score(),scores==null?null:scores.get(source.chunkId()),kept));
            if (kept) selected.add(new RagHit(new RagSource("S"+(selected.size()+1),source.chunkId(),source.documentId(),source.path(),source.title(),source.section(),
                    source.startLine(),source.endLine(),source.textHash(),source.score()),hit.text()));
        }
        String version=switch (strategy) { case NONE->"baseline-v1"; case LEXICAL->"unicode-overlap-v1"; case LLM->promptVersion; };
        var report=new RerankReport(strategy,effective,hits.size(),selected.size(),ranking,attempts,(System.nanoTime()-start)/1e6,
                reply==null?null:reply.providerInputTokens(),reply==null?null:reply.providerOutputTokens(),budget==null?null:budget.estimatedInputTokens(),
                budget==null?0:budget.inputBudget(),digest==null?"":digest,version,notes);
        return new Decision(new RagSearchResult(found.manifest(),selected),report);
    }
    private static Set<String> tokens(String text) {
        var words=new HashSet<String>(); var matcher=TOKEN.matcher(Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (matcher.find()) words.add(matcher.group());
        return words;
    }
    private static void fallback(List<String> notes,RuntimeException error) {
        if (!notes.contains("RERANK_FALLBACK")) notes.add("RERANK_FALLBACK");
        String code=error instanceof RagProblem problem?problem.code():"RERANK_MODEL_UNAVAILABLE";
        if (!notes.contains(code)) notes.add(code);
    }
}
