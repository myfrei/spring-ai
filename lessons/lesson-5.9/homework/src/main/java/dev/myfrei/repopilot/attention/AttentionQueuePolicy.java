package dev.myfrei.repopilot.attention;

import java.util.Objects;

/** Display selection only. Acknowledgements never approve, reject or cancel anything. */
public class AttentionQueuePolicy {
  public AttentionQueueReport check(AttentionQueueInput input) {
    Objects.requireNonNull(input);
    // TODO: select deduplicated pending approvals and unacknowledged terminal notices.
    throw new UnsupportedOperationException("Implement AttentionQueuePolicy.check");
  }
}
