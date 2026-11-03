package dev.myfrei.repopilot.ingestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import dev.myfrei.repopilot.context.CorpusDocument;
import dev.myfrei.repopilot.context.CorpusSnapshot;
import org.springframework.stereotype.Component;

@Component
public class MarkdownChunker {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");

    public List<Chunk> split(CorpusSnapshot corpus, ChunkingOptions options) {
        var result = new ArrayList<Chunk>();
        for (var document : corpus.documents().stream().sorted(Comparator.comparing(CorpusDocument::id)).toList()) {
            int ordinal = 0;
            var ranges = options.strategy() == ChunkingOptions.Strategy.FIXED_WINDOW
                    ? List.of(new Section(0, document.text().length(), document.title())) : sections(document);
            for (Section section : ranges) {
                int start = section.start();
                while (start < section.end()) {
                    int remaining = document.text().codePointCount(start, section.end());
                    int end = document.text().offsetByCodePoints(start, Math.min(options.maxCodePoints(), remaining));
                    String text = document.text().substring(start, end);
                    if (!text.isBlank()) {
                        String hash = Hashes.sha256(text);
                        String id = Hashes.identity(document.id(), document.path(), document.title(), section.title(), document.sha256(), options.identity(),
                                Integer.toString(ordinal), Integer.toString(start), Integer.toString(end), hash);
                        result.add(new Chunk(id, document.id(), document.path(), document.title(), section.title(), ordinal++,
                                lineAt(document.text(), start), lineAt(document.text(), end - 1), document.sha256(), hash,
                                corpus.version(), options.identity(), text));
                        if (result.size() > 512) throw new IllegalArgumentException("Too many chunks; increase size or reduce overlap");
                    }
                    if (end == section.end()) break;
                    start = document.text().offsetByCodePoints(end, -options.overlapCodePoints());
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Section> sections(CorpusDocument document) {
        var result = new ArrayList<Section>();
        var headings = new ArrayList<Heading>();
        int offset = 0;
        int start = 0;
        String title = document.title();
        char fence = 0;
        int fenceLength = 0;
        for (String line : document.text().split("(?<=\n)", -1)) {
            String stripped = line.strip();
            int indent = line.length() - line.stripLeading().length();
            char marker = stripped.isEmpty() ? 0 : stripped.charAt(0);
            int run = 0;
            while (run < stripped.length() && stripped.charAt(run) == marker) run++;
            if (indent <= 3 && (marker == '`' || marker == '~') && run >= 3) {
                if (fence == 0) { fence = marker; fenceLength = run; }
                else if (marker == fence && run >= fenceLength && stripped.substring(run).isBlank()) fence = 0;
            } else if (fence == 0) {
                var heading = HEADING.matcher(line.stripTrailing());
                if (heading.matches()) {
                    if (offset > start) result.add(new Section(start, offset, title));
                    int level = heading.group(1).length();
                    headings.removeIf(previous -> previous.level() >= level);
                    headings.add(new Heading(level, heading.group(2)));
                    title = String.join(" > ", headings.stream().map(Heading::title).toList());
                    start = offset;
                }
            }
            offset += line.length();
        }
        if (offset > start) result.add(new Section(start, offset, title));
        return result;
    }

    private static int lineAt(String text, int offset) {
        return 1 + (int) text.substring(0, offset).chars().filter(value -> value == '\n').count();
    }
    private record Section(int start, int end, String title) { }
    private record Heading(int level, String title) { }
}
