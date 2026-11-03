package dev.myfrei.repopilot.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;

public class EvaluationDataset {
    private final List<EvaluationCase> cases;
    private final String version;
    public EvaluationDataset(ResourceLoader loader, CorpusCatalog corpus) {
        try (var input = loader.getResource("classpath:evaluation/cases.json").getInputStream()) {
            byte[] bytes = input.readNBytes(131073);
            if (bytes.length > 131072) throw new IllegalArgumentException("Dataset too large");
            this.version = Hashes.sha256(new String(bytes, StandardCharsets.UTF_8));
            this.cases = List.of(JsonMapper.builder().build().readValue(bytes, EvaluationCase[].class));
            var ids = new HashSet<String>();
            for (var entry : this.cases) {
                if (!entry.id().matches("(dev|holdout)-[0-9]{2}") || !ids.add(entry.id())
                        || entry.split() == null || !entry.id().startsWith(entry.split() == Split.DEV ? "dev-" : "holdout-")
                        || entry.question().isBlank() || entry.question().length() > 4000 || entry.expectedAnswer().isBlank()) throw new IllegalArgumentException("Invalid case");
                for (var group : entry.evidenceGroups()) {
                    if (group.isEmpty()) throw new IllegalArgumentException("Empty evidence group");
                    for (var evidence : group) {
                        if (evidence.excerpt().isBlank() || !EvidenceCoverage.normalize(corpus.document(evidence.documentId()).text())
                                .contains(EvidenceCoverage.normalize(evidence.excerpt()))) throw new IllegalArgumentException("Gold evidence absent from corpus: " + entry.id());
                    }
                }
            }
            if (cases.stream().filter(entry -> entry.split() == Split.DEV).count() != 20
                    || cases.stream().filter(entry -> entry.split() == Split.HOLDOUT).count() != 10) throw new IllegalArgumentException("Require 20 DEV and 10 HOLDOUT");
        } catch (IOException error) { throw new IllegalStateException("Cannot load evaluation dataset", error); }
    }
    public String version() { return this.version; }
    public List<EvaluationCase> cases(Split split) { return this.cases.stream().filter(entry -> entry.split() == split).toList(); }
    public List<EvaluationCase> select(Request request) {
        var subset = cases(request.split());
        if (request.caseIds().isEmpty()) return subset;
        var selected = subset.stream().filter(entry -> request.caseIds().contains(entry.id())).toList();
        if (selected.size() != request.caseIds().size()) throw new IllegalArgumentException("Case ID outside selected split");
        return selected;
    }
}
