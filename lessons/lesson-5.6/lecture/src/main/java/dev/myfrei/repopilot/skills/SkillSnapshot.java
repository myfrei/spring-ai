package dev.myfrei.repopilot.skills;

import java.util.*;

/** Immutable bounded contents, pinned for a single run. */
public final class SkillSnapshot {
  record Bundle(SkillMetadata metadata, String instructions, Map<String, String> references) {}

  private final SkillCatalog catalog;
  private final Map<String, Bundle> bundles;

  SkillSnapshot(SkillCatalog catalog, Map<String, Bundle> bundles) {
    this.catalog = catalog;
    this.bundles = Map.copyOf(bundles);
  }

  public List<SkillMetadata> metadata() {
    return bundles.values().stream()
        .map(Bundle::metadata)
        .sorted(Comparator.comparing(SkillMetadata::name))
        .toList();
  }

  /**
   * Exact UTF-8 cost of the full successful activateSkill tool envelope. No activation side effect.
   */
  public int activationBytes(String name) {
    var b = bundle(name);
    var payload = new SkillSession.ActivatedSkill(name, b.metadata().version(), b.instructions());
    return dev.myfrei.repopilot.agent.AgentJson.bytes(
        dev.myfrei.repopilot.agent.AgentJson.encode(
            Map.of("ok", true, "code", "OK", "data", payload)));
  }

  Bundle bundle(String name) {
    SkillCatalog.validateName(name);
    Bundle bundle = bundles.get(name);
    if (bundle == null) throw SkillCatalog.error("SKILL_NOT_FOUND");
    return bundle;
  }

  void verifyCurrent(String name) {
    Bundle expected = bundle(name);
    Bundle actual = catalog.snapshot().bundles.get(name);
    if (actual == null || !expected.metadata().version().equals(actual.metadata().version()))
      throw SkillCatalog.error("STALE_SKILL");
  }
}
