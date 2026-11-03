package dev.myfrei.repopilot.evaluation;

import java.text.Normalizer;
import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.Source;
public final class EvidenceCoverage {
    private EvidenceCoverage() { }
    public static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }
    public static Boolean hit(EvaluationCase entry, List<Source> sources) {
        if (!entry.answerable()) return null;
        return entry.evidenceGroups().stream().anyMatch(group -> group.stream().allMatch(evidence -> sources.stream().anyMatch(source ->
                source.documentId().equals(evidence.documentId()) && normalize(source.text()).contains(normalize(evidence.excerpt())))));
    }
}
