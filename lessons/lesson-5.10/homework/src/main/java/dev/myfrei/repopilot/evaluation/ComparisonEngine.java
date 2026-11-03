package dev.myfrei.repopilot.evaluation;

import dev.myfrei.repopilot.diagnostics.TraceSession;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.Stage;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.Status;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import dev.myfrei.repopilot.chat.ChatStreamService;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

public class ComparisonEngine {
    private final CorpusCatalog corpus;
    private final ContextAssembler assembler;
    private final ObjectProvider<RagSearcher> searchers;
    private final ObjectProvider<RagPlanner> planners;
    private final ChatStreamService model;
    public ComparisonEngine(CorpusCatalog corpus, ContextAssembler assembler, ObjectProvider<RagSearcher> searchers,
            ObjectProvider<RagPlanner> planners, ChatStreamService model) {
        this.corpus = corpus; this.assembler = assembler; this.searchers = searchers; this.planners = planners; this.model = model;
    }
    public Result execute(int ordinal, EvaluationCase entry, int repetition, Mode mode, Request request) {
        return execute(ordinal, entry, repetition, mode, request, TraceSession.disabled());
    }
    public Result execute(int ordinal, EvaluationCase entry, int repetition, Mode mode, Request request, TraceSession trace) {
        var stage = new AtomicReference<>(Stage.REQUEST);
        var deltas = new java.util.concurrent.atomic.AtomicInteger();
        var firstText = new java.util.concurrent.atomic.AtomicBoolean();
        var timer = new CaseMeasurement(System::nanoTime);
        trace.event(Stage.REQUEST, Status.COMPLETED, Map.of("requestChars", entry.question().length()), Map.of(), "");
        var answer = new StringBuilder(); var outcome = new AtomicReference<>(Outcome.FAILED); var code = new AtomicReference<>("INCOMPLETE");
        List<Source> sources = List.of(); List<Source> retrieved = List.of(); Integer estimated = null; Integer inputBudget = null; String promptHash = "";
        try {
            List<Message> messages; List<Message> expanded; Advisor advisor = null;
            if (mode == Mode.RAG) {
                RagSearcher searcher = this.searchers.getIfAvailable(); RagPlanner planner = this.planners.getIfAvailable();
                if (searcher == null || planner == null) throw new EvaluationProblem("RAG_DISABLED", "RAG отключён.");
                stage.set(Stage.RETRIEVAL); trace.event(Stage.RETRIEVAL, Status.STARTED);
                timer.searchStarted(); RagSearchResult found;
                try { found = searcher.search(new RagRequest(entry.question(), request.topK(), request.threshold())); }
                finally { timer.searchFinished(); }
                trace.retrieved(found.hits());
                trace.event(Stage.RETRIEVAL, Status.COMPLETED, Map.of("retrieved", found.hits().size()), Map.of("indexFingerprint", found.manifest().fingerprint()), "");
                stage.set(Stage.ASSEMBLY); trace.event(Stage.ASSEMBLY, Status.STARTED);
                retrieved = found.hits().stream().map(hit -> source(hit.source(), hit.text())).toList();
                var plan = planner.plan(entry.question(), List.of(), found);
                sources = plan.report().sources().stream().map(source -> source(source,
                        found.hits().stream().filter(hit -> hit.source().chunkId().equals(source.chunkId())).findFirst().orElseThrow().text())).toList();
                trace.selected(plan.report().sources().stream().map(RagSource::chunkId).toList());
                estimated = plan.report().estimatedInputTokens(); inputBudget = plan.report().inputBudget(); messages = plan.baseMessages(); expanded = planner.projected(plan);
                if (plan.references().isEmpty()) {
                    timer.prepared();
                    trace.event(Stage.ASSEMBLY, Status.COMPLETED, Map.of("selected", 0, "estimated", 0, "inputBudget", inputBudget), Map.of(), "");
                    trace.event(Stage.GENERATION, Status.SKIPPED, Map.of(), Map.of(), "NO_EVIDENCE");
                    return new Result(ordinal, entry.id(), repetition, mode, entry.question(), Outcome.NO_EVIDENCE, RagChatService.NO_EVIDENCE,
                            "", timer.finish(), 0, null, null, retrieved.size(), List.of(), EvidenceCoverage.hit(entry, retrieved),
                            EvidenceCoverage.hit(entry, List.of()), "", trace.id());
                }
                advisor = dev.myfrei.repopilot.advisor.SnapshotAdvisorFactory.create(plan);
            } else {
                stage.set(Stage.ASSEMBLY); trace.event(Stage.ASSEMBLY, Status.STARTED);
                var plan = this.assembler.assemble(entry.question(), ContextMode.valueOf(mode.name()), List.of(), this.corpus.snapshot());
                messages = plan.messages(); expanded = messages; estimated = plan.report().estimatedInputTokens(); inputBudget = plan.report().inputBudget();
                sources = plan.report().sources().stream().map(source -> new Source(source.id(), source.id(), source.path(), "", this.corpus.document(source.id()).text())).toList();
            }
            promptHash = PromptFingerprint.of(expanded);
            trace.event(Stage.ASSEMBLY, Status.COMPLETED, Map.of("selected", sources.size(), "omitted", Math.max(0, retrieved.size() - sources.size()), "estimated", estimated, "inputBudget", inputBudget), Map.of("promptHash", promptHash), "");
            timer.prepared(); timer.modelStarted(); stage.set(Stage.GENERATION); trace.event(Stage.GENERATION, Status.STARTED);
            this.model.observed(messages, advisor, timer::usage).doOnNext(event -> {
                if (event.type().equals("delta")) {
                    timer.delta(event.text()); answer.append(event.text()); deltas.incrementAndGet();
                    if (!event.text().isBlank() && firstText.compareAndSet(false, true)) trace.event(Stage.FIRST_TEXT, Status.COMPLETED);
                }
                if (event.type().equals("completed")) { outcome.set(Outcome.COMPLETED); code.set(""); }
                if (event.type().equals("failed")) { outcome.set(Outcome.FAILED); code.set(event.code()); }
            }).blockLast();
            trace.event(Stage.GENERATION, outcome.get() == Outcome.COMPLETED ? Status.COMPLETED : Status.FAILED,
                    Map.of("deltas", deltas.get(), "answerChars", answer.length()), Map.of(), code.get());
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) { outcome.set(Outcome.CANCELLED); code.set("CANCELLED"); }
            else { outcome.set(Outcome.FAILED); code.set(error instanceof ConversationProblem problem ? problem.code() : "EXPERIMENT_ERROR"); }
            trace.event(stage.get(), outcome.get() == Outcome.CANCELLED ? Status.CANCELLED : Status.FAILED, Map.of(), Map.of(), code.get());
        }
        return new Result(ordinal, entry.id(), repetition, mode, entry.question(), outcome.get(), answer.toString(), code.get(), timer.finish(),
                estimated, timer.inputTokens(), timer.outputTokens(), retrieved.size(), sources, mode == Mode.RAG ? EvidenceCoverage.hit(entry, retrieved) : null,
                EvidenceCoverage.hit(entry, sources), promptHash, trace.id());
    }
    private static Source source(RagSource source, String text) { return new Source(source.label(), source.documentId(), source.path(), source.chunkId(), text); }
}
