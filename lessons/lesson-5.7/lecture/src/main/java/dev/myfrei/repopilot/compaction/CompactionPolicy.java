package dev.myfrei.repopilot.compaction;

import java.util.*;

/**
 * Pure eviction planning: oldest whole eligible units first; never split or remove protected data.
 */
public final class CompactionPolicy {
  public record Unit(String id, int estimatedTokens, boolean protectedUnit) {
    public Unit {
      if (id == null || id.isBlank() || estimatedTokens < 1)
        throw new IllegalArgumentException(
            "A bounded unit identifier and positive estimate are required");
    }
  }

  public record Decision(List<String> selected, int estimatedTokensRemoved, boolean enough) {
    public Decision {
      selected = List.copyOf(selected);
    }
  }

  public Decision choose(List<Unit> chronologicalUnits, int tokensToRemove) {
    if (chronologicalUnits == null || chronologicalUnits.size() > 40 || tokensToRemove < 0)
      throw new IllegalArgumentException("Bounded chronological units required");
    var units = List.copyOf(chronologicalUnits);
    if (units.stream().map(Unit::id).distinct().count() != units.size())
      throw new IllegalArgumentException("Unique unit identifiers required");
    var selected = new ArrayList<String>();
    int removed = 0;
    for (Unit unit : units) {
      if (removed >= tokensToRemove) break;
      if (unit.protectedUnit()) continue;
      selected.add(unit.id());
      removed = Math.addExact(removed, unit.estimatedTokens());
    }
    return new Decision(selected, removed, removed >= tokensToRemove);
  }
}
