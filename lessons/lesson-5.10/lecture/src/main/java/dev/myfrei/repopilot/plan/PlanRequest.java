package dev.myfrei.repopilot.plan;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.controlled.RunMode;
import java.util.*;

/** Closed HTTP input. Callers specify a plan, never successful evidence or completed states. */
public record PlanRequest(String question, RunMode mode, List<String> skills, PlanSpec plan) {
  public PlanRequest {
    new dev.myfrei.repopilot.agent.AgentRunRequest(question);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(plan);
    if (skills == null
        || skills.size() > 3
        || new HashSet<>(skills).size() != skills.size()
        || skills.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Distinct skills required");
    skills = List.copyOf(skills);
  }

  public static PlanRequest decode(String raw) {
    try {
      if (raw == null || AgentJson.bytes(raw) > 8192)
        throw new IllegalArgumentException("Bounded plan request");
      var a =
          AgentJson.arguments(
              raw,
              Set.of("question", "mode", "skills", "steps"),
              Set.of("question", "mode", "steps"));
      if (!(a.get("question") instanceof String question)
          || !(a.get("mode") instanceof String mode)
          || !(a.get("steps") instanceof List<?> list)
          || list.isEmpty()
          || list.size() > 4) throw new IllegalArgumentException("Plan fields required");
      var skills = new ArrayList<String>();
      if (a.containsKey("skills")) {
        if (!(a.get("skills") instanceof List<?> selected) || selected.size() > 3)
          throw new IllegalArgumentException("Skills list required");
        for (var value : selected) {
          if (!(value instanceof String s)) throw new IllegalArgumentException();
          skills.add(s);
        }
      }
      var steps = new ArrayList<PlanSpec.StepSpec>();
      for (var value : list) {
        if (!(value instanceof Map<?, ?> map)
            || !map.keySet()
                .equals(
                    Set.of(
                        "id",
                        "title",
                        "criterion",
                        "path",
                        "startLine",
                        "endLine",
                        "dependsOn",
                        "required"))
            || !(map.get("id") instanceof String id)
            || !(map.get("title") instanceof String title)
            || !(map.get("criterion") instanceof String criterion)
            || !(map.get("path") instanceof String path)
            || !(map.get("startLine") instanceof Integer start)
            || !(map.get("endLine") instanceof Integer end)
            || !(map.get("required") instanceof Boolean required)
            || !(map.get("dependsOn") instanceof List<?> dependencies))
          throw new IllegalArgumentException("Closed step required");
        var deps = new ArrayList<String>();
        for (var d : dependencies) {
          if (!(d instanceof String text)) throw new IllegalArgumentException();
          deps.add(text);
        }
        steps.add(new PlanSpec.StepSpec(id, title, criterion, path, start, end, deps, required));
      }
      return new PlanRequest(question, RunMode.valueOf(mode), skills, new PlanSpec(steps));
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid plan JSON");
    }
  }
}
