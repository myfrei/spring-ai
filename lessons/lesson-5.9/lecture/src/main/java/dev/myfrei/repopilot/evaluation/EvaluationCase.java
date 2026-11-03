package dev.myfrei.repopilot.evaluation;

import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.Split;
public record EvaluationCase(String id, Split split, String kind, String question, String expectedAnswer,
        List<List<Evidence>> evidenceGroups) {
    public EvaluationCase { evidenceGroups = evidenceGroups.stream().map(List::copyOf).toList(); }
    public boolean answerable() { return !evidenceGroups.isEmpty(); }
    public record Evidence(String documentId, String excerpt) { }
}
