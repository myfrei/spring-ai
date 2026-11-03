package dev.myfrei.repopilot.ingestion;

public record Chunk(String id, String documentId, String path, String title, String section, int ordinal,
        int startLine, int endLine, String documentHash, String textHash, String corpusVersion,
        String splitterVersion, String text) { }
