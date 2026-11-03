package dev.myfrei.repopilot.journalrecall;

import java.util.Objects;

/** Read-only preview. Never reruns a tool or grants access to another run. */
public class ToolRecallPolicy {
  public RecallReport check(RecallInput input) {
    Objects.requireNonNull(input);
    // TODO: select complete, current, same-run readFile results within the payload byte budget.
    throw new UnsupportedOperationException("Implement ToolRecallPolicy.check");
  }
}
