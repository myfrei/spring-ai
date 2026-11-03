package dev.myfrei.repopilot.workspace;

import java.util.*;

/** Bounded recent-run ledger. Eviction is explicit unavailability, never evidence for another run. */
public final class WorkspaceReadLedger {
    public static final int MAX_RUNS=128, MAX_RECEIPTS_PER_RUN=256, MAX_RETIRED_RUNS=256;
    private final LinkedHashMap<String,List<ReadReceipt>> runs=new LinkedHashMap<>(16,0.75f,true);
    private final Set<String> retired=new HashSet<>();
    public synchronized void record(ReadReceipt receipt) {
        validateRun(receipt.runId());
        if(this.retired.contains(receipt.runId()))throw new WorkspaceException("RECEIPTS_UNAVAILABLE");
        if(!this.runs.containsKey(receipt.runId())&&this.runs.size()>=MAX_RUNS) {
            // Never forget an eviction and accidentally resurrect a partial history.
            // When even bounded tombstones fill, reject new evidence rather than discard them.
            if(this.retired.size()>=MAX_RETIRED_RUNS)throw new WorkspaceException("RECEIPTS_UNAVAILABLE");
            String oldest=this.runs.keySet().iterator().next();this.runs.remove(oldest);this.retired.add(oldest);
        }
        var receipts=this.runs.computeIfAbsent(receipt.runId(),ignored->new ArrayList<>());
        if(!receipts.contains(receipt)) {
            if(receipts.size()>=MAX_RECEIPTS_PER_RUN)throw new WorkspaceException("RECEIPT_LIMIT");
            receipts.add(receipt);
        }
    }
    public synchronized List<ReadReceipt> receipts(String runId) {
        validateRun(runId);var receipts=this.runs.get(runId);
        if(receipts==null)throw new WorkspaceException("RECEIPTS_UNAVAILABLE");
        return List.copyOf(receipts);
    }
    static void validateRun(String runId) {
        try { if(runId==null||!UUID.fromString(runId).toString().equals(runId))throw new IllegalArgumentException(); }
        catch(IllegalArgumentException error){throw new WorkspaceException("INVALID_RUN_ID");}
    }
}
