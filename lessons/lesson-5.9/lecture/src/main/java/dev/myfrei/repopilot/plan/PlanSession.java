package dev.myfrei.repopilot.plan;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
import java.util.*;

/**
 * One run owns its plan. Model arguments never supply successful evidence or a completed status.
 */
public final class PlanSession {
  private static final class Step {
    final PlanSpec.StepSpec spec;
    String status = "PENDING", hash = "";

    Step(PlanSpec.StepSpec spec) {
      this.spec = spec;
    }
  }

  private final LinkedHashMap<String, Step> steps = new LinkedHashMap<>();
  private final WorkspaceService workspace;
  private UUID runId;
  private int revision = 1;

  public PlanSession(PlanSpec spec, WorkspaceService workspace) {
    this.workspace = Objects.requireNonNull(workspace);
    spec.steps().forEach(s -> steps.put(s.id(), new Step(s)));
  }

  public synchronized void bind(UUID id) {
    Objects.requireNonNull(id);
    if (runId != null && !runId.equals(id)) throw new PlanException("PLAN_RUN_MISMATCH");
    runId = id;
  }

  public synchronized PlanSnapshot view(UUID id) {
    bind(id);
    var views = new ArrayList<PlanSnapshot.StepView>();
    for (var step : steps.values()) {
      var s = step.spec;
      views.add(
          new PlanSnapshot.StepView(
              s.id(),
              s.title(),
              s.criterion(),
              s.path(),
              s.startLine(),
              s.endLine(),
              s.dependsOn(),
              s.required(),
              step.status,
              step.hash,
              current(step)));
    }
    return new PlanSnapshot(runId, revision, views);
  }

  public synchronized String prompt(UUID id) {
    var snapshot = view(id);
    return "CURRENT_PLAN_V1="
        + AgentJson.encode(snapshot)
        + "\n"
        + "План принадлежит Java-runtime. Начинай шаг startPlanStep только после его зависимостей,"
        + " читай точный диапазон readFile, затем проси completePlanStep. Результат complete"
        + " проверяется сервером; текст модели не завершает шаг. Незакрытые обязательные шаги или"
        + " устаревшее чтение запрещают финальный успех. READ_TEST означает чтение теста, а не его"
        + " запуск. revisePlanStep сбрасывает прогресс шага и его зависимых шагов после ошибки."
        + " Критерии, пути, зависимости, обязательность и вопрос пользователя остаются прежними.";
  }

  public synchronized String completionReason(UUID id) {
    bind(id);
    var relevant = new LinkedHashSet<String>();
    for (var step : steps.values()) if (step.spec.required()) closure(step.spec.id(), relevant);
    for (var key : relevant)
      if (!steps.get(key).status.equals("COMPLETED")) return "PLAN_INCOMPLETE";
    for (var key : relevant) if (!current(steps.get(key))) return "PLAN_EVIDENCE_STALE";
    return null;
  }

  private void closure(String id, Set<String> relevant) {
    if (relevant.add(id)) steps.get(id).spec.dependsOn().forEach(dep -> closure(dep, relevant));
  }

  public synchronized PlanSnapshot start(UUID id, String stepId) {
    bind(id);
    var step = required(stepId);
    if (step.status.equals("COMPLETED") && current(step))
      throw new PlanException("PLAN_ALREADY_COMPLETED");
    if (steps.values().stream().anyMatch(s -> s != step && s.status.equals("ACTIVE")))
      throw new PlanException("PLAN_STEP_ACTIVE");
    if (step.status.equals("ACTIVE")) throw new PlanException("PLAN_STEP_ACTIVE");
    dependencies(step);
    step.status = "ACTIVE";
    step.hash = "";
    revision++;
    return view(id);
  }

  public synchronized PlanSnapshot complete(UUID id, String stepId) {
    bind(id);
    var step = required(stepId);
    if (!step.status.equals("ACTIVE")) throw new PlanException("PLAN_STEP_NOT_ACTIVE");
    dependencies(step);
    String hash;
    List<ReadReceipt> receipts;
    try {
      hash = workspace.currentHash(step.spec.path());
      receipts = workspace.receipts(id.toString());
    } catch (WorkspaceException error) {
      throw new PlanException("PLAN_NOT_READ");
    }
    var ranges =
        receipts.stream()
            .filter(r -> r.path().equals(step.spec.path()) && r.fullFileHash().equals(hash))
            .sorted(Comparator.comparingInt(ReadReceipt::startLine))
            .toList();
    long next = step.spec.startLine();
    for (var range : ranges) {
      if (range.startLine() > next) break;
      if (range.endLine() >= next) next = (long) range.endLine() + 1;
    }
    if (next <= step.spec.endLine()) throw new PlanException("PLAN_NOT_READ");
    step.status = "COMPLETED";
    step.hash = hash;
    revision++;
    return view(id);
  }

  public synchronized PlanSnapshot revise(UUID id, String stepId, String reason) {
    bind(id);
    required(stepId);
    if (reason == null
        || reason.isBlank()
        || AgentJson.bytes(reason) > 240
        || reason.codePoints().anyMatch(Character::isISOControl))
      throw new PlanException("PLAN_INVALID_REVISION");
    // Requirements remain immutable: only progress and evidence are reset.
    for (var step : steps.values()) {
      if (step.spec.id().equals(stepId) || dependsOn(step.spec.id(), stepId, new HashSet<>())) {
        step.status = "PENDING";
        step.hash = "";
      }
    }
    revision++;
    return view(id);
  }

  private boolean dependsOn(String id, String wanted, Set<String> seen) {
    if (!seen.add(id)) return false;
    for (String dependency : steps.get(id).spec.dependsOn())
      if (dependency.equals(wanted) || dependsOn(dependency, wanted, seen)) return true;
    return false;
  }

  private void dependencies(Step step) {
    var ancestors = new LinkedHashSet<String>();
    step.spec.dependsOn().forEach(id -> closure(id, ancestors));
    for (String id : ancestors) {
      var dependency = steps.get(id);
      if (!dependency.status.equals("COMPLETED"))
        throw new PlanException("PLAN_DEPENDENCY_PENDING");
      if (!current(dependency)) throw new PlanException("PLAN_DEPENDENCY_STALE");
    }
  }

  private boolean current(Step step) {
    if (!step.status.equals("COMPLETED") || step.hash.isEmpty()) return false;
    try {
      return step.hash.equals(workspace.currentHash(step.spec.path()));
    } catch (WorkspaceException unavailable) {
      return false;
    }
  }

  private Step required(String id) {
    var step = steps.get(id);
    if (step == null) throw new PlanException("PLAN_STEP_NOT_FOUND");
    return step;
  }

  public List<AgentTool> tools() {
    var id = Map.of("type", "string");
    return List.of(
        tool(
            "planStatus",
            "Read the current server-owned plan; no step is completed by prose.",
            Map.of(),
            (a, c) -> view(c.runId())),
        tool(
            "startPlanStep",
            "Start one step only after completed, current dependencies. One active step.",
            Map.of("stepId", id),
            (a, c) -> start(c.runId(), text(a, "stepId"))),
        tool(
            "completePlanStep",
            "Request completion; Java requires same-run read receipts and full current line"
                + " coverage. READ_TEST is reading, not running tests.",
            Map.of("stepId", id),
            (a, c) -> complete(c.runId(), text(a, "stepId"))),
        tool(
            "revisePlanStep",
            "Reset a step and its dependent progress to PENDING after an error. Requirements never"
                + " change; provide a short reason, not new paths or criteria.",
            Map.of("stepId", id, "reason", Map.of("type", "string")),
            (a, c) -> revise(c.runId(), text(a, "stepId"), text(a, "reason"))));
  }

  private AgentTool tool(
      String name,
      String description,
      Map<String, ?> properties,
      java.util.function.BiFunction<Map<String, Object>, AgentToolContext, PlanSnapshot> action) {
    String schema =
        AgentJson.encode(
            Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                properties.keySet().stream().sorted().toList(),
                "additionalProperties",
                false));
    return new AgentTool() {
      public String name() {
        return name;
      }

      public String description() {
        return description;
      }

      public String inputSchema() {
        return schema;
      }

      public AgentToolResult execute(String raw, AgentToolContext context) {
        try {
          if (context == null || context.runId() == null)
            throw new PlanException("PLAN_RUN_MISMATCH");
          var result =
              action.apply(
                  AgentJson.arguments(raw, properties.keySet(), properties.keySet()), context);
          String json = AgentJson.encode(result);
          if (AgentJson.bytes(AgentJson.encode(Map.of("ok", true, "code", "OK", "data", result)))
              > 4096) throw new PlanException("PLAN_OUTPUT_LIMIT");
          return AgentToolResult.success(json);
        } catch (PlanException e) {
          return AgentToolResult.error(
              e.code(),
              AgentJson.encode(
                  Map.of(
                      "code",
                      e.code(),
                      "message",
                      "Шаг не выполнен; проверьте фактические доказательства и зависимости.")));
        } catch (RuntimeException invalid) {
          return AgentToolResult.error(
              "PLAN_INVALID_ARGUMENTS", "{\"message\":\"Проверьте аргументы шага.\"}");
        }
      }
    };
  }

  private static String text(Map<String, Object> args, String key) {
    if (!(args.get(key) instanceof String value)) throw new PlanException("PLAN_INVALID_ARGUMENTS");
    return value;
  }
}
