package dev.myfrei.repopilot.plan;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** A bounded ordered DAG, not executable model-generated commands. */
public record PlanSpec(List<StepSpec> steps) {
  public static final int MAX_STEPS = 4;

  public PlanSpec {
    if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS)
      throw new IllegalArgumentException("One to four plan steps required");
    steps = List.copyOf(steps);
    var seen = new HashSet<String>();
    boolean required = false;
    for (var step : steps) {
      if (step == null || seen.contains(step.id()) || !seen.containsAll(step.dependsOn()))
        throw new IllegalArgumentException("Unique ordered acyclic dependencies required");
      seen.add(step.id());
      required |= step.required();
    }
    if (!required) throw new IllegalArgumentException("At least one required step");
    // Before any run begins, prove the largest possible successful snapshot fits its full envelope.
    var largest =
        steps.stream()
            .map(
                s ->
                    new PlanSnapshot.StepView(
                        s.id(),
                        s.title(),
                        s.criterion(),
                        s.path(),
                        s.startLine(),
                        s.endLine(),
                        s.dependsOn(),
                        s.required(),
                        "COMPLETED",
                        "a".repeat(64),
                        false))
            .toList();
    var snapshot = new PlanSnapshot(new UUID(0, 0), Integer.MAX_VALUE, largest);
    if (AgentJson.bytes(AgentJson.encode(Map.of("ok", true, "code", "OK", "data", snapshot)))
        > 4096) throw new IllegalArgumentException("Plan snapshot exceeds tool envelope limit");
  }

  public record StepSpec(
      String id,
      String title,
      String criterion,
      String path,
      int startLine,
      int endLine,
      List<String> dependsOn,
      boolean required) {
    public StepSpec {
      if (id == null
          || !id.matches("[a-z][a-z0-9-]{0,15}")
          || title == null
          || title.isBlank()
          || AgentJson.bytes(title) > 120
          || title.codePoints().anyMatch(Character::isISOControl)
          || criterion == null
          || !Set.of("READ_CODE", "READ_TEST").contains(criterion)
          || path == null
          || AgentJson.bytes(path) > 240
          || !path.matches("src/(main|test)/[A-Za-z0-9_/-]+\\.java")
          || startLine < 1
          || endLine < startLine
          || endLine > 100000
          || (long) endLine - startLine + 1 > 80
          || dependsOn == null
          || dependsOn.size() > 3
          || new HashSet<>(dependsOn).size() != dependsOn.size())
        throw new IllegalArgumentException("Invalid bounded plan step");
      if (criterion.equals("READ_CODE") && !path.startsWith("src/main/")
          || criterion.equals("READ_TEST") && !path.startsWith("src/test/"))
        throw new IllegalArgumentException("Criterion must match source/test scope");
      for (String part : path.split("/")) {
        if (part.isEmpty() || part.startsWith("."))
          throw new IllegalArgumentException("Normalized path required");
        String low = part.toLowerCase(Locale.ROOT);
        if (List.of("secret", "credential", "password", "token", "privatekey").stream()
            .anyMatch(low::contains)) throw new IllegalArgumentException("Excluded path");
      }
      dependsOn = List.copyOf(dependsOn);
    }
  }
}
