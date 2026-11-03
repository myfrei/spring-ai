package dev.myfrei.repopilot.compaction;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CompactionPolicyTest {
  private final CompactionPolicy policy = new CompactionPolicy();

  @Test
  void choosesOldestWholeUnitsRatherThanLargestAndMayOvershoot() {
    var result = policy.choose(List.of(unit("old", 20, false), unit("new", 100, false)), 10);
    assertThat(result.selected()).containsExactly("old");
    assertThat(result.estimatedTokensRemoved()).isEqualTo(20);
    assertThat(result.enough()).isTrue();
  }

  @Test
  void neverSelectsProtectedUnitsAndReportsInsufficientRemovableData() {
    var result =
        policy.choose(
            List.of(unit("rules", 1000, true), unit("old", 20, false), unit("latest", 1000, true)),
            30);
    assertThat(result.selected()).containsExactly("old");
    assertThat(result.enough()).isFalse();
  }

  @Test
  void noNeedMeansNoDeletionAndSelectionIsImmutable() {
    var result = policy.choose(List.of(unit("old", 100, false)), 0);
    assertThat(result.selected()).isEmpty();
    assertThat(result.enough()).isTrue();
    assertThatThrownBy(() -> result.selected().add("other"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsDuplicateIdsMissingUnitsAndOverflowInsteadOfGuessing() {
    assertThatThrownBy(
            () -> policy.choose(List.of(unit("same", 1, false), unit("same", 2, false)), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.choose(null, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.choose(List.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CompactionPolicy.Unit("x", 0, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CompactionPolicy.Unit unit(String id, int cost, boolean protectedUnit) {
    return new CompactionPolicy.Unit(id, cost, protectedUnit);
  }
}
