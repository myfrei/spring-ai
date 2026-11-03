package dev.myfrei.repopilot.advisor;

import dev.myfrei.repopilot.rag.RagSearchResult;

/** Server-owned document profiles; callers cannot supply an arbitrary filter. */
public final class DocumentScope {
    public enum Profile { TROUBLESHOOTING, ARCHITECTURE }
    private DocumentScope() { }
    public static RagSearchResult select(Profile profile, RagSearchResult found) {
        // TODO HW-3.2: новая policy профиля источников ДО расчёта бюджета.
        // TROUBLESHOOTING: runbook; ARCHITECTURE: overview/runtime.
        // Сохранить rank, metadata и manifest; перенумеровать S1..Sn.
        throw new UnsupportedOperationException("TODO HW-3.2 document scope policy");
    }
}
