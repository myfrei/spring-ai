package dev.myfrei.repopilot.selection;

import dev.myfrei.repopilot.skills.SkillCatalog;
import java.util.*;

/** Each check obtains a fresh immutable server catalogue; no Skill is activated. */
public class SelectionChecker {
  private final SkillCatalog catalog;
  private final SkillSelectionPolicy policy;

  public SelectionChecker(SkillCatalog catalog, SkillSelectionPolicy policy) {
    this.catalog = Objects.requireNonNull(catalog);
    this.policy = Objects.requireNonNull(policy);
  }

  public SelectionReport check(SelectionRequest request) {
    var snapshot = catalog.snapshot();
    var candidates =
        snapshot.metadata().stream()
            .map(
                m ->
                    new SelectionInput.Candidate(
                        m.name(), m.version(), snapshot.activationBytes(m.name())))
            .toList();
    return policy.check(new SelectionInput(request.skills(), candidates, request.budgetBytes()));
  }
}
