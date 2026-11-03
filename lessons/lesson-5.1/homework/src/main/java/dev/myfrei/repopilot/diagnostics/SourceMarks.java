package dev.myfrei.repopilot.diagnostics;

import java.util.List;
import java.util.HashSet;
import dev.myfrei.repopilot.rag.RagHit;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.SourceMark;
public final class SourceMarks {
    private SourceMarks() { }
    public static List<SourceMark> from(List<RagHit> hits, List<String> selectedIds) {
        var all = new HashSet<String>();
        for (var hit : hits) if (!all.add(hit.source().chunkId())) throw new IllegalArgumentException("Duplicate retrieved ID");
        if (new HashSet<>(selectedIds).size() != selectedIds.size() || !all.containsAll(selectedIds)) throw new IllegalArgumentException("Selection outside retrieval");
        return hits.stream().map(hit -> new SourceMark(hit.source().documentId(), hit.source().chunkId(), hit.source().textHash(),
                hit.source().score(), selectedIds.contains(hit.source().chunkId()))).toList();
    }
}
