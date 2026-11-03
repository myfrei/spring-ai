package dev.myfrei.repopilot.evaluation;

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
        var timer = new CaseMeasurement(System::nanoTime);
        var answer = new StringBuilder(); var outcome = new AtomicReference<>(Outcome.FAILED); var code = new AtomicReference<>("INCOMPLETE");
        List<Source> sources = List.of(); List<Source> retrieved = List.of(); Integer estimated = null; String promptHash = "";
        try {
            List<Message> messages; List<Message> expanded; Advisor advisor = null;
            if (mode == Mode.RAG) {
                RagSearcher searcher = this.searchers.getIfAvailable(); RagPlanner planner = this.planners.getIfAvailable();
                if (searcher == null || planner == null) throw new EvaluationProblem("RAG_DISABLED", "RAG отключён.");
                timer.searchStarted(); RagSearchResult found;
                try { found = searcher.search(new RagRequest(entry.question(), request.topK(), request.threshold())); }
                finally { timer.searchFinished(); }
                retrieved = found.hits().stream().map(hit -> source(hit.source(), hit.text())).toList();
                var plan = planner.plan(entry.question(), List.of(), found);
                sources = plan.report().sources().stream().map(source -> source(source,
                        found.hits().stream().filter(hit -> hit.source().chunkId().equals(source.chunkId())).findFirst().orElseThrow().text())).toList();
                estimated = plan.report().estimatedInputTokens(); messages = plan.baseMessages(); expanded = planner.projected(plan);
                if (plan.references().isEmpty()) {
                    timer.prepared();
                    return new Result(ordinal, entry.id(), repetition, mode, entry.question(), Outcome.NO_EVIDENCE, RagChatService.NO_EVIDENCE,
                            "", timer.finish(), 0, null, null, retrieved.size(), List.of(), EvidenceCoverage.hit(entry, retrieved),
                            EvidenceCoverage.hit(entry, List.of()), "");
                }
                advisor = QuestionAnswerAdvisor.builder(new SelectedDocumentsStore(entry.question(), plan.references()))
                        .promptTemplate(RagPrompt.template()).searchRequest(SearchRequest.builder().topK(plan.references().size()).build()).build();
            } else {
                var plan = this.assembler.assemble(entry.question(), ContextMode.valueOf(mode.name()), List.of(), this.corpus.snapshot());
                messages = plan.messages(); expanded = messages; estimated = plan.report().estimatedInputTokens();
                sources = plan.report().sources().stream().map(source -> new Source(source.id(), source.id(), source.path(), "", this.corpus.document(source.id()).text())).toList();
            }
            promptHash = PromptFingerprint.of(expanded);
            timer.prepared(); timer.modelStarted();
            this.model.observed(messages, advisor, timer::usage).doOnNext(event -> {
                if (event.type().equals("delta")) { timer.delta(event.text()); answer.append(event.text()); }
                if (event.type().equals("completed")) { outcome.set(Outcome.COMPLETED); code.set(""); }
                if (event.type().equals("failed")) { outcome.set(Outcome.FAILED); code.set(event.code()); }
            }).blockLast();
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) { outcome.set(Outcome.CANCELLED); code.set("CANCELLED"); }
            else { outcome.set(Outcome.FAILED); code.set(error instanceof ConversationProblem problem ? problem.code() : "EXPERIMENT_ERROR"); }
        }
        return new Result(ordinal, entry.id(), repetition, mode, entry.question(), outcome.get(), answer.toString(), code.get(), timer.finish(),
                estimated, timer.inputTokens(), timer.outputTokens(), retrieved.size(), sources, mode == Mode.RAG ? EvidenceCoverage.hit(entry, retrieved) : null,
                EvidenceCoverage.hit(entry, sources), promptHash);
    }
    private static Source source(RagSource source, String text) { return new Source(source.label(), source.documentId(), source.path(), source.chunkId(), text); }
}
