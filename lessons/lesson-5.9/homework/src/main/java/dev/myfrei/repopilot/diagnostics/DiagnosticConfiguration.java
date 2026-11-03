package dev.myfrei.repopilot.diagnostics;

import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.evaluation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration
@ConditionalOnProperty(name = {"repopilot.diagnostics.enabled", "repopilot.evaluation.enabled"}, havingValue = "true")
public class DiagnosticConfiguration {
    @Bean TraceStore traceStore(@Value("${repopilot.diagnostics.trace-directory}") String directory) { return new TraceStore(directory); }
    @Bean TraceRecorder traceRecorder(TraceStore store) { return new TraceRecorder(store); }
    @Bean DiagnosticService diagnosticService(RunStore runs, EvaluationDataset dataset, CorpusCatalog corpus, TraceStore traces) { return new DiagnosticService(runs, dataset, corpus, traces); }
    @Bean DiagnosticLab diagnosticLab(CorpusCatalog corpus, EvaluationDataset dataset, TraceRecorder traces) { return new DiagnosticLab(corpus, dataset, traces); }
}
