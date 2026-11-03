package dev.myfrei.repopilot.ingestion;

public record ChunkingOptions(Strategy strategy, int maxCodePoints, int overlapCodePoints) {
    public static final String VERSION = "atx-codepoints-v1";
    public enum Strategy { FIXED_WINDOW, MARKDOWN_SECTIONS }
    public ChunkingOptions {
        if (strategy == null || maxCodePoints < 64 || maxCodePoints > 2000
                || overlapCodePoints < 0 || overlapCodePoints >= maxCodePoints) {
            throw new IllegalArgumentException("Use size 64..2000 and overlap 0..size-1");
        }
    }
    public String identity() { return VERSION + ":" + this.strategy + ":" + this.maxCodePoints + ":" + this.overlapCodePoints; }
}
