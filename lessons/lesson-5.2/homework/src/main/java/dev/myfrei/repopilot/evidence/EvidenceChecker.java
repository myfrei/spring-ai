package dev.myfrei.repopilot.evidence;
import java.util.*;
import java.util.function.Function;
import dev.myfrei.repopilot.workspace.*;
/** Provided provenance adapter. Only server-owned successful reads authorize a current-hash lookup. */
public final class EvidenceChecker {
 private final Function<String,List<ReadReceipt>> receipts;
 private final Function<String,String> currentHash;
 private final EvidencePolicy policy;
 public EvidenceChecker(WorkspaceService workspace,EvidencePolicy policy){this(workspace::receipts,workspace::currentHash,policy);}
 EvidenceChecker(Function<String,List<ReadReceipt>> receipts,Function<String,String> currentHash,EvidencePolicy policy){this.receipts=Objects.requireNonNull(receipts);this.currentHash=Objects.requireNonNull(currentHash);this.policy=Objects.requireNonNull(policy);}
 public EvidenceReport check(UUID runId,List<EvidenceInput.Claim> claims){
  var reads=receipts.apply(runId.toString()).stream().map(read->new EvidenceInput.ReadSpan(UUID.fromString(read.runId()),read.path(),read.fullFileHash(),read.startLine(),read.endLine())).toList();
  var hashes=new LinkedHashMap<String,String>();var attempted=new HashSet<String>();
  for(var claim:claims){
   boolean observed=reads.stream().anyMatch(read->read.runId().equals(runId)&&read.path().equals(claim.path())&&read.fullFileHash().equals(claim.fullFileHash()));
   if(!observed||!attempted.add(claim.path()))continue;
   try{hashes.put(claim.path(),currentHash.apply(claim.path()));}catch(WorkspaceException unavailable){/* No current hash is conservative UNAVAILABLE, never assumed equality. */}
  }
  return policy.check(new EvidenceInput(runId,claims,reads,hashes));
 }
}
