package dev.myfrei.repopilot.homework;

import java.util.*;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.stereotype.Component;
@Component
public class PairedRunComparator {
    public enum PairState { COMPARED, UNASSESSED, EXECUTION_REGRESSION, EXECUTION_RECOVERY, BOTH_UNSUCCESSFUL, ONLY_BASELINE, ONLY_CANDIDATE }
    public record Pair(String caseId,int repetition,Integer baselineOrdinal,Integer candidateOrdinal,PairState state,Integer answerDelta,Integer citationDelta,Double totalMsDelta,boolean regression,boolean improvement) { }
    public record Report(UUID baselineId,UUID candidateId,Mode mode,List<Pair> pairs,int qualityCompared,int regressions,int improvements,int unpaired,int unassessed) { public Report { pairs=List.copyOf(pairs); } }
    private record Key(String id,int repetition) { }
    public Report compare(Experiment baseline, Experiment candidate, Mode mode) {
        // TODO HW-2.4: реализовать новое поведение из README.
        throw new UnsupportedOperationException("TODO HW-2.4");
    }
    private static Map<Key,Result> index(Experiment run,Mode mode) {
        var result=new HashMap<Key,Result>();
        for(var row:run.results()) if(row.mode()==mode) {var key=new Key(row.caseId(),row.repetition());if(result.putIfAbsent(key,row)!=null)throw new IllegalArgumentException("Duplicate case/repetition");}
        return result;
    }
    private static boolean eligible(Result row) { return row.outcome()==Outcome.COMPLETED || row.outcome()==Outcome.NO_EVIDENCE; }
}
