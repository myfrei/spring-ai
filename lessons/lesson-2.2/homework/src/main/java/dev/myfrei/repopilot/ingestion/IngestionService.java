package dev.myfrei.repopilot.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.context.CorpusSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {
    private final CorpusCatalog corpus;
    private final MarkdownChunker chunker;
    private final OllamaEmbeddingGateway embeddings;
    private final VectorValidator validator;
    private final IndexArtifactStore store;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    public IngestionService(CorpusCatalog corpus, MarkdownChunker chunker, OllamaEmbeddingGateway embeddings,
            VectorValidator validator, IndexArtifactStore store, @Value("${repopilot.ingestion.batch-size}") int batchSize) {
        if (batchSize < 1 || batchSize > 64) throw new IllegalArgumentException("Invalid batch size");
        this.corpus = corpus; this.chunker = chunker; this.embeddings = embeddings;
        this.validator = validator; this.store = store; this.batchSize = batchSize;
    }

    public Preview preview(ChunkingOptions options) {
        CorpusSnapshot snapshot = this.corpus.snapshot();
        return new Preview(snapshot.version(), options, this.chunker.split(snapshot, options));
    }

    public Result index(ChunkingOptions options) { return runIndex(options, null).result(); }

    public IncrementalResult incrementalIndex(ChunkingOptions options, dev.myfrei.repopilot.homework.IncrementalEmbeddingPlanner planner) {
        return runIndex(options, java.util.Objects.requireNonNull(planner));
    }

    private IncrementalResult runIndex(ChunkingOptions options, dev.myfrei.repopilot.homework.IncrementalEmbeddingPlanner planner) {
        if (!this.running.compareAndSet(false,true)) throw new IngestionProblem("INDEX_BUSY","Индексация уже выполняется.");
        try {
            Preview preview=preview(options); if(preview.chunks().isEmpty()) throw new IllegalArgumentException("No chunks to index");
            var identity=this.embeddings.identity(); String fingerprint=IndexArtifact.fingerprint(preview.corpusVersion(),options,identity);
            var previous=this.store.load();
            if(previous.isPresent() && previous.get().fingerprint().equals(fingerprint)) return new IncrementalResult(preview.chunks().size(),0,result(previous.get(),true));
            var plan=planner==null ? new dev.myfrei.repopilot.homework.IncrementalEmbeddingPlanner.Plan(List.of(),preview.chunks()) : planner.plan(preview.chunks(),identity,previous);
            var byId=new java.util.HashMap<String,EmbeddedChunk>(); for(var entry:plan.reusable()) byId.put(entry.chunk().id(),entry);
            for(int start=0;start<plan.missing().size();start+=this.batchSize) {
                var batch=plan.missing().subList(start,Math.min(start+this.batchSize,plan.missing().size()));
                var vectors=this.validator.validate(this.embeddings.embed(batch),batch.size(),identity.dimensions());
                for(int i=0;i<batch.size();i++) byId.put(batch.get(i).id(),new EmbeddedChunk(batch.get(i),vectors.get(i)));
            }
            if(!identity.equals(this.embeddings.identity())) throw new IngestionProblem("MODEL_CHANGED","Embedding profile изменился; снимок не опубликован.");
            var entries=preview.chunks().stream().map(chunk -> java.util.Objects.requireNonNull(byId.get(chunk.id()),"Missing planned chunk")).toList();
            var artifact=new IndexArtifact(1,fingerprint,preview.corpusVersion(),options,identity,entries); this.store.save(artifact);
            return new IncrementalResult(plan.reusable().size(),plan.missing().size(),result(artifact,false));
        } finally { this.running.set(false); }
    }
    public record IncrementalResult(int reusedVectors,int embeddedVectors,Result result) { }

    public Status status() {
        var artifact = this.store.load();
        return new Status(artifact.isPresent(), this.running.get(), this.corpus.snapshot().version(), this.embeddings.configuredModel(), this.embeddings.configuredDimensions(),
                "RUNTIME_NOT_CHECKED", artifact.map(value -> result(value, false)).orElse(null));
    }

    private static Result result(IndexArtifact artifact, boolean reused) {
        return new Result(reused, artifact.fingerprint(), artifact.corpusVersion(), artifact.options(), artifact.embedding(), artifact.chunks().size());
    }

    public record Preview(String corpusVersion, ChunkingOptions options, List<Chunk> chunks) {
        public Preview { chunks = List.copyOf(chunks); }
    }
    public record Result(boolean reused, String fingerprint, String corpusVersion, ChunkingOptions options,
            EmbeddingIdentity embedding, int chunkCount) { }
    public record Status(boolean indexed, boolean running, String currentCorpusVersion, String configuredModel, int configuredDimensions,
            String runtimeStatus, Result saved) { }
}
