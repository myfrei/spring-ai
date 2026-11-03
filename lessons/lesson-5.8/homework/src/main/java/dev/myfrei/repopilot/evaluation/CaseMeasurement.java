package dev.myfrei.repopilot.evaluation;

import java.util.function.LongSupplier;
import org.springframework.ai.chat.model.ChatResponse;
import dev.myfrei.repopilot.evaluation.Experiment.Timing;
public class CaseMeasurement {
    private final LongSupplier clock;
    private final long start;
    private long prepared;
    private long retrievalStart;
    private long retrievalNanos;
    private Long modelStart;
    private Long firstText;
    private Integer inputTokens;
    private Integer outputTokens;
    public CaseMeasurement(LongSupplier clock) { this.clock = clock; this.start = clock.getAsLong(); this.prepared = this.start; }
    public synchronized void searchStarted() { this.retrievalStart = this.clock.getAsLong(); }
    public synchronized void searchFinished() { this.retrievalNanos += this.clock.getAsLong() - this.retrievalStart; }
    public synchronized void prepared() { this.prepared = this.clock.getAsLong(); }
    public synchronized void modelStarted() { this.modelStart = this.clock.getAsLong(); }
    public synchronized void delta(String text) { if (this.modelStart != null && this.firstText == null && text != null && !text.isBlank()) this.firstText = this.clock.getAsLong(); }
    public synchronized void usage(ChatResponse response) {
        var usage = response.getMetadata().getUsage();
        if (usage != null) {
            if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) this.inputTokens = usage.getPromptTokens();
            if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) this.outputTokens = usage.getCompletionTokens();
        }
    }
    public synchronized Integer inputTokens() { return this.inputTokens; }
    public synchronized Integer outputTokens() { return this.outputTokens; }
    public synchronized Timing finish() {
        long end = this.clock.getAsLong();
        return new Timing(ms(end - this.start), ms((this.modelStart == null ? end : this.prepared) - this.start), ms(this.retrievalNanos),
                this.modelStart == null ? null : ms(end - this.modelStart), this.firstText == null ? null : ms(this.firstText - this.start),
                this.firstText == null ? null : ms(this.firstText - this.modelStart));
    }
    private static double ms(long nanos) { return Math.max(0, nanos) / 1_000_000.0; }
}
