package dev.myfrei.repopilot.context;

import java.util.List;

public record CorpusSnapshot(String version, List<CorpusDocument> documents) {
    public CorpusSnapshot { documents = List.copyOf(documents); }
}
