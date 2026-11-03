package dev.myfrei.repopilot.context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class CorpusCatalog {
    private final CorpusSnapshot snapshot;

    public CorpusCatalog(ResourceLoader loader) {
        try {
            var documents = new ArrayList<CorpusDocument>();
            var ids = new HashSet<String>();
            int total = 0;
            for (String line : read(loader.getResource("classpath:corpus/manifest.txt"), 4096).split("\n")) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\\|", -1);
                if (fields.length != 3 || !fields[0].matches("[a-z0-9-]{1,40}") || !ids.add(fields[0])
                        || !fields[1].matches("[A-Za-z0-9_/-]+\\.md") || fields[1].startsWith("/")
                        || fields[1].contains("..") || fields[1].contains("//") || fields[2].isBlank()) {
                    throw new IllegalArgumentException("Invalid corpus manifest");
                }
                String text = read(loader.getResource("classpath:corpus/" + fields[1]), 16_384);
                if (text.isBlank()) throw new IllegalArgumentException("Empty corpus document");
                total = Math.addExact(total, text.getBytes(StandardCharsets.UTF_8).length);
                if (total > 65_536 || documents.size() >= 16) throw new IllegalArgumentException("Corpus too large");
                documents.add(new CorpusDocument(fields[0], fields[1], fields[2], hash(text), text));
            }
            if (documents.isEmpty()) throw new IllegalArgumentException("Empty corpus");
            documents.sort(Comparator.comparing(CorpusDocument::id));
            var identity = new StringBuilder();
            for (var document : documents) {
                for (String field : new String[] {document.id(), document.path(), document.title(), document.sha256()}) {
                    identity.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
                }
            }
            this.snapshot = new CorpusSnapshot(hash(identity.toString()), documents);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load bundled corpus", ex);
        }
    }

    public CorpusSnapshot snapshot() { return this.snapshot; }

    public CorpusDocument document(String id) {
        return this.snapshot.documents().stream().filter(document -> document.id().equals(id)).findFirst()
                .orElseThrow(() -> new dev.myfrei.repopilot.conversation.ConversationProblem("NOT_FOUND", "Документ не найден."));
    }

    private static String read(Resource resource, int maxBytes) throws IOException {
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IllegalArgumentException("Corpus resource too large");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                    .replace("\r\n", "\n").replace('\r', '\n');
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
