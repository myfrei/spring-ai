package dev.myfrei.repopilot.selection;

import java.util.Objects;

/** Pure preview: selection does not activate a Skill or grant tool permissions. */
public class SkillSelectionPolicy {
  public SelectionReport check(SelectionInput input) {
    Objects.requireNonNull(input);
    // TODO: ordered selection with version pins, deduplication and a byte budget.
    throw new UnsupportedOperationException("Implement SkillSelectionPolicy.check");
  }
}
