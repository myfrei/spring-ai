package dev.myfrei.repopilot.triage;
import java.util.*;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport;
import dev.myfrei.repopilot.diagnostics.DiagnosticReport.Category;
import dev.myfrei.repopilot.evaluation.Experiment.Mode;
public final class TriageQueue {
 private TriageQueue(){}
 public record Item(String caseId,Mode mode,Category category,int priority,List<Integer> ordinals){public Item{ordinals=List.copyOf(ordinals);}}
 public record Queue(int totalGroups,int totalRows,List<Item> items){public Queue{items=List.copyOf(items);}}
 public static Queue build(DiagnosticReport report,int limit){
  // TODO: новая очередь разбора, не реализация классификатора из лекции.
  throw new UnsupportedOperationException("TODO TRIAGE");
 }
}
