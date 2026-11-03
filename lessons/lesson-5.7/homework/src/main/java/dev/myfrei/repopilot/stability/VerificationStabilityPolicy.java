package dev.myfrei.repopilot.stability;

import java.util.Objects;

/** Historical observations only: does not execute checks or certify the current workspace. */
public class VerificationStabilityPolicy {
  public VerificationStabilityReport check(VerificationStabilityInput input) {
    Objects.requireNonNull(input);
    // TODO: deduplicate scoped reports, group exact execution contracts and classify observations.
    throw new UnsupportedOperationException("Implement VerificationStabilityPolicy.check");
  }
}
