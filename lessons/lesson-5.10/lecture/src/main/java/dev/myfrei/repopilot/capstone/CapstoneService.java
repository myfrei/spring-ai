package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.compaction.ContextRequest;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.skills.*;
import dev.myfrei.repopilot.ui.*;
import dev.myfrei.repopilot.verification.*;
import dev.myfrei.repopilot.workspace.*;
import java.time.*;
import java.util.*;

/**
 * One physical teaching workspace, one imported task, explicit jobs and human action boundaries.
 */
public final class CapstoneService {
  private record Preparation(UUID jobId, UUID baselineId, Instant submittedAt) {}

  private record Review(
      UUID jobId, UUID preparationId, UUID patchId, UUID passingId, Instant submittedAt) {}

  private final CapstoneTaskGateway gateway;
  private final WorkspaceService workspace;
  private final AgentStore store;
  private final SessionCoordinator sessions;
  private final VerificationService verification;
  private final ControlledService controlled;
  private final SkillCatalog skills;
  private final Clock clock;
  private final String verifierImageId;
  private CapstoneSession session;
  private final Map<UUID, Preparation> preparations = new LinkedHashMap<>();
  private final Map<UUID, Review> reviews = new LinkedHashMap<>();
  private UUID selectedPreparation, selectedReview;

  public CapstoneService(
      CapstoneTaskGateway gateway,
      WorkspaceService workspace,
      AgentStore store,
      SessionCoordinator sessions,
      VerificationService verification,
      ControlledService controlled,
      SkillCatalog skills,
      Clock clock,
      String verifierImageId) {
    this.gateway = Objects.requireNonNull(gateway);
    this.workspace = Objects.requireNonNull(workspace);
    this.store = Objects.requireNonNull(store);
    this.sessions = Objects.requireNonNull(sessions);
    this.verification = Objects.requireNonNull(verification);
    this.controlled = Objects.requireNonNull(controlled);
    this.skills = Objects.requireNonNull(skills);
    this.clock = Objects.requireNonNull(clock);
    if (verifierImageId == null || !verifierImageId.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException("Exact configured verifier image required");
    this.verifierImageId = verifierImageId;
  }

  public synchronized CapstoneSession create(String taskId) {
    if (!CapstoneTaskContract.TASK.equals(taskId)) throw new CapstoneProblem("CAPSTONE_TASK_ID");
    if (session != null) return session;
    var imported = gateway.importTask(taskId);
    var task = CapstoneTaskContract.decode(imported.task().fields());
    if (!validImport(imported.receipt(), task)) throw new CapstoneProblem("CAPSTONE_MCP_PROOF");
    if (!task.baselineHash().equals(currentHash(task.sourcePath())))
      throw new CapstoneProblem("CAPSTONE_BASELINE_MISMATCH");
    session =
        new CapstoneSession(
            UUID.randomUUID(),
            store.createConversation().id(),
            task,
            imported.receipt(),
            clock.instant());
    return session;
  }

  public synchronized CapstoneSession session(UUID id) {
    return required(id);
  }

  public synchronized SessionSnapshot prepare(UUID id, UUID requestId, UUID baselineReportId) {
    var exercise = required(id);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(baselineReportId);
    if (preparations.containsKey(requestId)) {
      var old = preparations.get(requestId);
      if (!old.baselineId().equals(baselineReportId))
        throw new CapstoneProblem("CAPSTONE_REQUEST_CONFLICT");
      return sessions.snapshot(old.jobId());
    }
    if (preparations.size() >= 2 || selectedReview != null)
      throw new CapstoneProblem("CAPSTONE_PREPARE_LIMIT");
    var failure = verification.report(baselineReportId);
    if (!baseline(failure, exercise)
        || !exercise.task().baselineHash().equals(currentHash(exercise.task().sourcePath())))
      throw new CapstoneProblem("CAPSTONE_BASELINE_REQUIRED");
    String question =
        "CAPSTONE_PREPARE "
            + exercise.task().id()
            + " taskDigest="
            + CapstoneTaskContract.digest(exercise.task())
            + ". Прочитай readVerification reportId="
            + baselineReportId
            + ". Активный java-code-review уже дан. Начни code, прочитай точные строки StockLedger,"
            + " закрой шаг и предложи один точечный patch для ошибки равенства остатку. Тесты не"
            + " объявляй пройденными. proposePatch не меняет файл; жди отдельного решения"
            + " человека.";
    var snapshot =
        sessions.submit(
            exercise.conversationId(), requestId, task(question, RunMode.EDIT, exercise.task()));
    preparations.put(requestId, new Preparation(snapshot.id(), baselineReportId, clock.instant()));
    selectedPreparation = requestId;
    return snapshot;
  }

  public synchronized SessionSnapshot review(
      UUID id, UUID requestId, UUID patchId, UUID passingReportId) {
    var exercise = required(id);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(patchId);
    Objects.requireNonNull(passingReportId);
    if (reviews.containsKey(requestId)) {
      var old = reviews.get(requestId);
      if (!old.patchId().equals(patchId) || !old.passingId().equals(passingReportId))
        throw new CapstoneProblem("CAPSTONE_REQUEST_CONFLICT");
      return sessions.snapshot(old.jobId());
    }
    if (reviews.size() >= 2) throw new CapstoneProblem("CAPSTONE_REVIEW_LIMIT");
    var patch = controlled.proposal(patchId);
    UUID binding = null;
    for (var entry : preparations.entrySet()) {
      var candidate = sessions.snapshot(entry.getValue().jobId());
      if (candidate.result() != null && candidate.result().run().id().equals(patch.runId())) {
        binding = entry.getKey();
        break;
      }
    }
    if (binding == null
        || !patch.conversationId().equals(exercise.conversationId())
        || !patch.path().equals(exercise.task().sourcePath())
        || !patch.status().equals("APPLIED")) throw new CapstoneProblem("CAPSTONE_PATCH_REQUIRED");
    var preparation = preparations.get(binding);
    var prepared = sessions.snapshot(preparation.jobId());
    var passed = verification.report(passingReportId);
    var failed = verification.report(preparation.baselineId());
    if (!baseline(failed, exercise)
        || !observed(prepared.runId(), failed.id())
        || !patch.baseHash().equals(exercise.task().baselineHash())
        || patch.createdAt().isBefore(failed.finishedAt())
        || !passed.imageId().equals(failed.imageId())
        || !completed(prepared)
        || !plan(prepared, exercise.task(), patch.baseHash())
        || !skill(prepared)
        || !ownRead(prepared.runId(), exercise.task(), patch.baseHash())
        || !freshPass(passed, patch, exercise))
      throw new CapstoneProblem("CAPSTONE_FRESH_PASS_REQUIRED");
    String question =
        "CAPSTONE_REVIEW "
            + exercise.task().id()
            + " taskDigest="
            + CapstoneTaskContract.digest(exercise.task())
            + ". Прочитай readVerification reportId="
            + passingReportId
            + ". После человеческого применения patchId="
            + patchId
            + " перечитай текущие строки, начни и закрой обязательный code. Объясни только"
            + " фиксированный StockLedger contract, отдели PASS от непроверенных предположений;"
            + " никаких новых записей и approvals.";
    var snapshot =
        sessions.submit(
            exercise.conversationId(), requestId, task(question, RunMode.ASK, exercise.task()));
    reviews.put(
        requestId, new Review(snapshot.id(), binding, patchId, passingReportId, clock.instant()));
    selectedReview = requestId;
    selectedPreparation = binding;
    return snapshot;
  }

  public synchronized CapstoneReport report(UUID id) {
    var exercise = required(id);
    var task = exercise.task();
    var preparation = selectedPreparation == null ? null : preparations.get(selectedPreparation);
    var review = selectedReview == null ? null : reviews.get(selectedReview);
    var prepared = preparation == null ? null : sessions.snapshot(preparation.jobId());
    var reviewed = review == null ? null : sessions.snapshot(review.jobId());
    var failed = preparation == null ? null : verification.report(preparation.baselineId());
    PatchProposal patch = review == null ? null : controlled.proposal(review.patchId());
    if (patch == null && prepared != null && prepared.runId() != null) {
      var possible =
          controlled.proposals(prepared.runId()).stream()
              .filter(p -> p.path().equals(task.sourcePath()))
              .toList();
      if (possible.size() == 1) patch = possible.getFirst();
    }
    var passed = review == null ? null : verification.report(review.passingId());
    String currentHash = currentHash(task.sourcePath());
    String currentRevision = currentHash == null ? null : revision(currentHash);
    var gates = new ArrayList<CapstoneGate>();
    add(gates, "MCP_IMPORTED", validImport(exercise.importReceipt(), task));
    add(gates, "BASELINE_FAIL", failed != null && baseline(failed, exercise));
    add(
        gates,
        "BASELINE_OBSERVED",
        prepared != null && failed != null && observed(prepared.runId(), failed.id()));
    add(gates, "PREPARE_COMPLETED", completed(prepared));
    add(gates, "PREPARE_PLAN", plan(prepared, task, task.baselineHash()));
    add(gates, "SKILL_ACTIVATED", skill(prepared));
    add(
        gates,
        "PREPARE_READ",
        prepared != null && ownRead(prepared.runId(), task, task.baselineHash()));
    boolean applied =
        patch != null
            && patch.status().equals("APPLIED")
            && patch.conversationId().equals(exercise.conversationId())
            && prepared != null
            && Objects.equals(patch.runId(), prepared.runId())
            && patch.path().equals(task.sourcePath())
            && controlled.appliedAt(patch.id()) != null;
    add(gates, "PATCH_APPLIED", applied);
    add(
        gates,
        "PATCH_VERSION_CHAIN",
        patch != null
            && failed != null
            && patch.baseHash().equals(task.baselineHash())
            && failed.inputRevision().equals(revision(patch.baseHash()))
            && !patch.baseHash().equals(patch.resultHash())
            && !patch.createdAt().isBefore(failed.finishedAt()));
    add(
        gates,
        "FRESH_PASS",
        applied
            && passed != null
            && failed != null
            && passed.imageId().equals(failed.imageId())
            && freshPass(passed, patch, exercise)
            && review != null
            && !review.submittedAt().isBefore(passed.finishedAt()));
    add(gates, "CURRENT_VERSION", applied && patch.resultHash().equals(currentHash));
    add(gates, "REVIEW_COMPLETED", completed(reviewed));
    add(gates, "REVIEW_PLAN", patch != null && plan(reviewed, task, patch.resultHash()));
    add(
        gates,
        "REVIEW_READ",
        reviewed != null && patch != null && ownRead(reviewed.runId(), task, patch.resultHash()));
    add(
        gates,
        "PASS_OBSERVED",
        reviewed != null && passed != null && observed(reviewed.runId(), passed.id()));
    String image = verifierImageId;
    return new CapstoneReport(
        exercise.id(),
        task.id(),
        exercise.conversationId(),
        new CapstoneCompletion().state(gates),
        CapstoneTaskContract.digest(task),
        exercise.importReceipt(),
        gates,
        preparation == null ? null : preparation.jobId(),
        review == null ? null : review.jobId(),
        failed == null ? null : failed.id(),
        passed == null ? null : passed.id(),
        patch == null ? null : patch.id(),
        failed == null ? null : failed.inputRevision(),
        patch == null ? null : revision(patch.resultHash()),
        currentRevision,
        image,
        task.sourcePath(),
        patch == null ? null : patch.diff(),
        clock.instant(),
        evidence(prepared),
        evidence(reviewed));
  }

  private CapstoneRunEvidence evidence(SessionSnapshot job) {
    if (job == null || job.result() == null || job.runId() == null) return null;
    List<ReadReceipt> reads;
    try {
      reads = workspace.receipts(job.runId().toString());
    } catch (WorkspaceException unavailable) {
      reads = List.of();
    }
    var result = job.result();
    return new CapstoneRunEvidence(
        job.id(),
        job.runId(),
        result.run().status(),
        result.run().stopReason(),
        result.plan(),
        result.activations(),
        reads,
        result.run().report(),
        result.research().budget());
  }

  private ResearchRequest task(String question, RunMode mode, StockRoomTask task) {
    var spec =
        new PlanSpec(
            List.of(
                new PlanSpec.StepSpec(
                    "code",
                    "Прочитать условие остатка",
                    "READ_CODE",
                    task.sourcePath(),
                    task.startLine(),
                    task.endLine(),
                    List.of(),
                    true)));
    return new ResearchRequest(
        new ContextRequest(
            new PlanRequest(question, mode, List.of("java-code-review"), spec), 12000),
        ResearchLimits.DEFAULT);
  }

  private boolean baseline(VerificationReport report, CapstoneSession exercise) {
    return report.conversationId().equals(exercise.conversationId())
        && report.command().equals(exercise.task().command())
        && report.imageId().equals(verifierImageId)
        && report.status().equals("FAIL")
        && Integer.valueOf(10).equals(report.exitCode())
        && report.inputsStillCurrent()
        && report.inputRevision().equals(revision(exercise.task().baselineHash()));
  }

  private boolean freshPass(
      VerificationReport report, PatchProposal patch, CapstoneSession exercise) {
    Instant applied = controlled.appliedAt(patch.id());
    var state = verification.state(exercise.conversationId());
    return applied != null
        && report.conversationId().equals(exercise.conversationId())
        && report.command().equals(exercise.task().command())
        && report.imageId().equals(verifierImageId)
        && report.status().equals("PASS")
        && Integer.valueOf(0).equals(report.exitCode())
        && report.inputsStillCurrent()
        && report.inputRevision().equals(revision(patch.resultHash()))
        && !report.startedAt().isBefore(applied)
        && state.state().equals("CHECKED_PASS")
        && report.id().equals(state.reportId())
        && patch.resultHash().equals(currentHash(patch.path()));
  }

  private boolean completed(SessionSnapshot job) {
    return job != null
        && job.result() != null
        && job.result().run().status().equals("COMPLETED")
        && Objects.equals(job.runId(), job.result().run().id())
        && job.conversationId().equals(session.conversationId());
  }

  private boolean plan(SessionSnapshot job, StockRoomTask task, String hash) {
    if (job == null || job.result() == null) return false;
    var plan = job.result().plan();
    if (plan == null || !Objects.equals(plan.runId(), job.runId()) || plan.steps().size() != 1)
      return false;
    var step = plan.steps().getFirst();
    return step.id().equals("code")
        && step.criterion().equals("READ_CODE")
        && step.required()
        && step.path().equals(task.sourcePath())
        && step.startLine() == task.startLine()
        && step.endLine() == task.endLine()
        && step.status().equals("COMPLETED")
        && step.evidenceHash().equals(hash);
  }

  private boolean skill(SessionSnapshot job) {
    if (job == null || job.result() == null) return false;
    try {
      var version =
          skills.snapshot().metadata().stream()
              .filter(s -> s.name().equals("java-code-review"))
              .findFirst()
              .orElseThrow()
              .version();
      return job.result().activations().stream()
          .anyMatch(s -> s.name().equals("java-code-review") && s.version().equals(version));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private boolean ownRead(UUID run, StockRoomTask task, String hash) {
    if (run == null || hash == null) return false;
    try {
      return ResearchReports.covered(
          workspace.receipts(run.toString()),
          run,
          new ResearchFinding(
              "Capstone read provenance",
              task.sourcePath(),
              hash,
              task.startLine(),
              task.endLine()));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private boolean observed(UUID run, UUID report) {
    if (run == null) return false;
    var calls = new HashMap<String, AgentMessage.ToolCall>();
    try {
      for (var message : store.messages(run)) {
        for (var call : message.toolCalls()) calls.put(call.id(), call);
        for (var reply : message.toolResults()) {
          var call = calls.get(reply.id());
          if (call == null
              || !"readVerification".equals(call.name())
              || !call.name().equals(reply.name())) continue;
          var envelope =
              AgentJson.arguments(
                  reply.resultJson(), Set.of("ok", "code", "data"), Set.of("ok", "code", "data"));
          if (Boolean.TRUE.equals(envelope.get("ok"))
              && envelope.get("data") instanceof Map<?, ?> data
              && report.toString().equals(data.get("reportId"))) return true;
        }
      }
    } catch (RuntimeException invalid) {
      return false;
    }
    return false;
  }

  private static boolean validImport(McpImportReceipt receipt, StockRoomTask task) {
    return receipt != null
        && CapstoneTaskContract.SERVER.equals(receipt.serverName())
        && CapstoneTaskContract.TOOL.equals(receipt.toolName())
        && "2025-11-25".equals(receipt.protocolVersion())
        && receipt.initializeCalls() == 1
        && receipt.listCalls() == 1
        && receipt.toolCalls() == 1
        && CapstoneTaskContract.digest(task).equals(receipt.taskDigest())
        && receipt.importedAt() != null;
  }

  private String currentHash(String path) {
    try {
      return workspace.currentHash(path);
    } catch (WorkspaceException unavailable) {
      return null;
    }
  }

  private static String revision(String fileHash) {
    return Hashes.sha256(AgentJson.canonical(Map.of(VerificationService.SOURCE, fileHash)));
  }

  private CapstoneSession required(UUID id) {
    if (session == null || !session.id().equals(id))
      throw new CapstoneProblem("CAPSTONE_SESSION_NOT_FOUND");
    return session;
  }

  private static void add(List<CapstoneGate> gates, String code, boolean passed) {
    gates.add(new CapstoneGate(code, passed));
  }
}
