package dev.myfrei.repopilot.rag;

import java.util.LinkedHashMap;
import dev.myfrei.repopilot.ingestion.EmbeddedChunk;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.ingestion.IndexArtifact;
import org.springframework.ai.document.Document;

public final class RagDocuments {
    private RagDocuments() { }
    public static String rowId(String workspace, String fingerprint, String chunkId) {
        return Hashes.identity(workspace, fingerprint, chunkId);
    }
    public static Document document(String workspace, IndexArtifact artifact, EmbeddedChunk entry) {
        var chunk = entry.chunk(); var metadata = new LinkedHashMap<String, Object>();
        metadata.put("workspaceId", workspace); metadata.put("fingerprint", artifact.fingerprint());
        metadata.put("corpusVersion", artifact.corpusVersion()); metadata.put("embeddingDigest", artifact.embedding().digest());
        metadata.put("chunkId", chunk.id()); metadata.put("documentId", chunk.documentId());
        metadata.put("path", chunk.path()); metadata.put("title", chunk.title()); metadata.put("section", chunk.section());
        metadata.put("startLine", chunk.startLine()); metadata.put("endLine", chunk.endLine());
        metadata.put("documentHash", chunk.documentHash()); metadata.put("textHash", chunk.textHash());
        return Document.builder().id(rowId(workspace, artifact.fingerprint(), chunk.id())).text(chunk.text()).metadata(metadata).build();
    }
}
