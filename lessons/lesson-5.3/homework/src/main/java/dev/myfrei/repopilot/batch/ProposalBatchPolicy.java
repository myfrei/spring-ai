package dev.myfrei.repopilot.batch;

import java.util.Objects;

/** Pure preflight: this policy must never approve or modify any file. */
public class ProposalBatchPolicy {
  public BatchReport check(BatchInput input) {
    Objects.requireNonNull(input);
    // TODO: classify every item in order and return ready only when all are READY.
    throw new UnsupportedOperationException("Implement ProposalBatchPolicy.check");
  }
}
