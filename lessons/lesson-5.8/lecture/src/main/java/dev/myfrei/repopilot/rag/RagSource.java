package dev.myfrei.repopilot.rag;

public record RagSource(String label, String chunkId, String documentId, String path, String title, String section,
        int startLine, int endLine, String textHash, double score) { }
