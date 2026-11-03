package dev.myfrei.repopilot.ingestion;

public class IngestionProblem extends RuntimeException {
    private final String code;
    public IngestionProblem(String code, String message) { super(message); this.code = code; }
    public String code() { return this.code; }
}
