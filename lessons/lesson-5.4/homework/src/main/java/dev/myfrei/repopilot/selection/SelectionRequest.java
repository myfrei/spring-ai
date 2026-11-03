package dev.myfrei.repopilot.selection;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** Closed caller contract: never accepts catalogue bodies or byte costs. */
public record SelectionRequest(List<SelectionInput.Pin> skills, int budgetBytes) {
  public SelectionRequest {
    new SelectionInput(skills, List.of(), budgetBytes);
    skills = List.copyOf(skills);
  }

  public static SelectionRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 4096)
        throw new IllegalArgumentException("Selection JSON too large");
      var args =
          AgentJson.arguments(
              raw, Set.of("skills", "budgetBytes"), Set.of("skills", "budgetBytes"));
      if (!(args.get("budgetBytes") instanceof Integer budget)
          || !(args.get("skills") instanceof List<?> list)
          || list.isEmpty()
          || list.size() > 12)
        throw new IllegalArgumentException("Expected skills and integer budgetBytes");
      var pins = new ArrayList<SelectionInput.Pin>();
      for (var entry : list) {
        if (!(entry instanceof Map<?, ?> map)
            || !map.keySet().equals(Set.of("name", "version"))
            || !(map.get("name") instanceof String name)
            || !(map.get("version") instanceof String version))
          throw new IllegalArgumentException("Expected name/version only");
        pins.add(new SelectionInput.Pin(name, version));
      }
      return new SelectionRequest(pins, budget);
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid selection JSON");
    }
  }
}
