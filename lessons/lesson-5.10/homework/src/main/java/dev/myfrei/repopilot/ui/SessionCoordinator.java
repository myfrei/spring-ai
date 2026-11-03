package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.skills.SkillRuntime;
import dev.myfrei.repopilot.verification.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async command/event boundary. It dispatches the existing runtime exactly once, never another
 * loop.
 */
public final class SessionCoordinator {
  public static final int MAX_JOBS = 64, MAX_EVENTS = 128, MAX_APPROVALS = 256;

  @FunctionalInterface
  public interface Dispatcher {
    ResearchRuntime.ResearchRunResult run(
        UUID conversation, ResearchRequest task, java.util.function.BooleanSupplier cancelled);
  }

  public record EventPage(
      UUID sessionId,
      List<SessionEvent> events,
      long nextSequence,
      long firstAvailableSequence,
      long lastSequence,
      boolean terminal) {
    public EventPage {
      events = List.copyOf(events);
    }
  }

  private record Key(UUID conversation, UUID request) {}

  private final class Job {
    final UUID id = UUID.randomUUID(), conversation, request;
    final String hash;
    final ResearchRequest task;
    final AtomicBoolean cancel = new AtomicBoolean();
    final ArrayDeque<SessionEvent> events = new ArrayDeque<>();
    String status = "QUEUED", reason = "";
    UUID runId;
    long sequence;
    int waiters;
    ResearchRuntime.ResearchRunResult result;

    Job(UUID conversation, UUID request, String hash, ResearchRequest task) {
      this.conversation = conversation;
      this.request = request;
      this.hash = hash;
      this.task = task;
      append("QUEUED", Map.of());
    }

    synchronized void append(String type, Map<String, Object> attributes) {
      events.addLast(new SessionEvent(++sequence, type, attributes, Instant.now()));
      while (events.size() > eventLimit) events.removeFirst();
      notifyAll();
    }

    synchronized boolean terminal() {
      return Set.of("COMPLETED", "STOPPED", "FAILED").contains(status);
    }
  }

  private final Dispatcher dispatcher;
  private final ResearchRuntime research;
  private final AgentStore store;
  private final PlanRuntime plans;
  private final SkillRuntime skills;
  private final ControlledService controlled;
  private final VerificationService verification;
  private final Map<UUID, Job> jobs = new LinkedHashMap<>();
  private final Map<Key, UUID> requests = new HashMap<>();
  private final Map<UUID, UUID> active = new HashMap<>();
  private final Semaphore slots;
  private final int eventLimit;

  public SessionCoordinator(
      ResearchRuntime research,
      AgentStore store,
      PlanRuntime plans,
      SkillRuntime skills,
      ControlledService controlled,
      VerificationService verification) {
    this(research::run, research, store, plans, skills, controlled, verification, 2, MAX_EVENTS);
  }

  public SessionCoordinator(
      Dispatcher dispatcher,
      ResearchRuntime research,
      AgentStore store,
      PlanRuntime plans,
      SkillRuntime skills,
      ControlledService controlled,
      VerificationService verification,
      int concurrency,
      int eventLimit) {
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.research = Objects.requireNonNull(research);
    this.store = Objects.requireNonNull(store);
    this.plans = Objects.requireNonNull(plans);
    this.skills = Objects.requireNonNull(skills);
    this.controlled = Objects.requireNonNull(controlled);
    this.verification = Objects.requireNonNull(verification);
    if (concurrency < 1 || concurrency > 2 || eventLimit < 2 || eventLimit > MAX_EVENTS)
      throw new IllegalArgumentException("Bounded coordinator");
    this.slots = new Semaphore(concurrency, true);
    this.eventLimit = eventLimit;
  }

  public SessionSnapshot submit(UUID conversation, UUID requestId, ResearchRequest task) {
    store.conversation(conversation);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(task);
    String hash = Hashes.sha256(AgentJson.encode(task));
    Job job;
    synchronized (jobs) {
      var key = new Key(conversation, requestId);
      UUID existing = requests.get(key);
      if (existing != null) {
        job = jobs.get(existing);
        if (!job.hash.equals(hash)) throw new SessionProblem("SESSION_REQUEST_CONFLICT");
      } else {
        if (jobs.size() >= MAX_JOBS) throw new SessionProblem("SESSION_CAPACITY");
        if (active.containsKey(conversation)) throw new SessionProblem("SESSION_BUSY");
        job = new Job(conversation, requestId, hash, task);
        jobs.put(job.id, job);
        requests.put(key, job.id);
        active.put(conversation, job.id);
        Job submitted = job;
        Thread.startVirtualThread(() -> dispatch(submitted));
      }
    }
    return snapshot(job.id);
  }

  private void dispatch(Job job) {
    boolean admitted = false;
    try {
      while (!job.cancel.get()) {
        if (slots.tryAcquire(50, TimeUnit.MILLISECONDS)) {
          admitted = true;
          break;
        }
      }
      if (!admitted || job.cancel.get()) {
        finish(job, "STOPPED", "CANCELLED_BEFORE_START", null);
        return;
      }
      try (var observation =
          store.observeRun(
              id -> {
                synchronized (job) {
                  job.runId = id;
                  job.status = "RUNNING";
                }
                job.append("RUN_BOUND", Map.of("runId", id.toString()));
              },
              event -> job.append(event.type(), event.attributes()))) {
        var result = dispatcher.run(job.conversation, job.task, job.cancel::get);
        finish(job, result.run().status(), result.run().stopReason(), result);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      finish(job, "FAILED", "SESSION_INTERRUPTED", null);
    } catch (AgentProblem failure) {
      finish(job, "FAILED", failure.code(), null);
    } catch (RuntimeException failure) {
      finish(job, "FAILED", "SESSION_EXECUTION_FAILED", null);
    } finally {
      if (admitted) slots.release();
      synchronized (jobs) {
        active.remove(job.conversation, job.id);
      }
    }
  }

  private void finish(
      Job job, String status, String reason, ResearchRuntime.ResearchRunResult result) {
    synchronized (jobs) {
      active.remove(job.conversation, job.id);
    }
    synchronized (job) {
      job.status = status;
      job.reason = reason;
      job.result = result;
      job.append("SESSION_FINISHED", Map.of("status", status, "reason", reason));
    }
  }

  private Job required(UUID id) {
    synchronized (jobs) {
      var job = jobs.get(id);
      if (job == null) throw new SessionProblem("SESSION_NOT_FOUND");
      return job;
    }
  }

  public SessionSnapshot snapshot(UUID id) {
    var job = required(id);
    UUID run;
    String status, reason;
    long last, first;
    ResearchRuntime.ResearchRunResult result;
    synchronized (job) {
      run = job.runId;
      status = job.status;
      reason = job.reason;
      last = job.sequence;
      first = job.events.getFirst().sequence();
      result = job.result;
    }
    // Never hold a job monitor while calling stores/runtime: AgentStore notifications acquire it.
    PlanSnapshot plan = null;
    ResearchSnapshot child = null;
    if (run != null) {
      try {
        plan = plans.snapshot(run);
      } catch (PlanException pending) {
      }
      try {
        child = research.snapshot(run);
      } catch (ResearchException pending) {
      }
    }
    var approvals = new ArrayList<ApprovalRef>();
    if (run != null)
      for (var p : controlled.proposals(run))
        if (Set.of("PENDING", "PREVIEW").contains(p.status()))
          approvals.add(new ApprovalRef("PATCH", p.id(), p.status(), "Правка " + p.path()));
    for (var p : verification.requests(job.conversation))
      if (p.status().equals("PENDING"))
        approvals.add(
            new ApprovalRef(
                "VERIFICATION",
                p.id(),
                p.status(),
                "Проверка " + p.command() + " версии " + p.inputRevision().substring(0, 12)));
    if (approvals.size() > MAX_APPROVALS) throw new IllegalStateException("Approval bound");
    return new SessionSnapshot(
        job.id,
        job.conversation,
        job.request,
        status,
        run,
        reason,
        last,
        first,
        result,
        approvals,
        plan,
        child,
        verification.state(job.conversation));
  }

  public List<SessionSnapshot> sessions(UUID conversation) {
    store.conversation(conversation);
    List<UUID> ids;
    synchronized (jobs) {
      ids =
          jobs.values().stream()
              .filter(j -> j.conversation.equals(conversation))
              .map(j -> j.id)
              .toList();
    }
    return ids.stream().map(this::snapshot).toList();
  }

  public EventPage events(UUID id, long after, int waitMs) {
    if (after < 0 || waitMs < 0 || waitMs > 10000)
      throw new IllegalArgumentException("Event cursor/wait bounds");
    var job = required(id);
    synchronized (job) {
      if (job.waiters >= 8) throw new SessionProblem("SESSION_READER_CAPACITY");
      job.waiters++;
      try {
        long until = System.nanoTime() + waitMs * 1_000_000L;
        while (after == job.sequence && !job.terminal()) {
          long left = until - System.nanoTime();
          if (left <= 0) break;
          TimeUnit.NANOSECONDS.timedWait(job, left);
        }
        long first = job.events.getFirst().sequence();
        if (after < first - 1) throw new SessionProblem("SESSION_EVENTS_EXPIRED");
        if (after > job.sequence) throw new IllegalArgumentException("Future cursor");
        var rows = job.events.stream().filter(e -> e.sequence() > after).toList();
        long next = rows.isEmpty() ? after : rows.getLast().sequence();
        return new EventPage(job.id, rows, next, first, job.sequence, job.terminal());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new SessionProblem("SESSION_READER_INTERRUPTED");
      } finally {
        job.waiters--;
      }
    }
  }

  public Object approval(UUID id, String kind, UUID approvalId) {
    var job = required(id);
    UUID run;
    synchronized (job) {
      run = job.runId;
    }
    if (kind.equals("PATCH")) {
      var p = controlled.proposal(approvalId);
      if (run == null || !run.equals(p.runId()) || !job.conversation.equals(p.conversationId()))
        throw new SessionProblem("SESSION_APPROVAL_SCOPE");
      return p;
    }
    if (kind.equals("VERIFICATION")) {
      var p = verification.request(approvalId);
      if (!job.conversation.equals(p.conversationId()))
        throw new SessionProblem("SESSION_APPROVAL_SCOPE");
      return p;
    }
    throw new IllegalArgumentException("Approval kind");
  }

  public SessionSnapshot command(UUID id, SessionCommand command) {
    var job = required(id);
    switch (command.type()) {
      case "CANCEL" -> {
        if (!job.terminal()) {
          job.cancel.set(true);
          job.append("CANCEL_REQUESTED", Map.of());
        }
      }
      case "REQUEST_VERIFICATION" -> {
        var request = verification.request(job.conversation, VerificationService.COMMAND);
        job.append("VERIFICATION_REQUESTED", Map.of("approvalId", request.id().toString()));
      }
      case "APPROVE_PATCH", "REJECT_PATCH" -> {
        approval(id, "PATCH", command.approvalId());
        var result =
            controlled.decide(
                command.approvalId(),
                command.type().equals("APPROVE_PATCH") ? "APPROVE" : "REJECT");
        job.append(
            "PATCH_DECIDED",
            Map.of("approvalId", result.id().toString(), "status", result.status()));
      }
      case "APPROVE_VERIFICATION", "REJECT_VERIFICATION" -> {
        approval(id, "VERIFICATION", command.approvalId());
        var result =
            verification.decide(
                command.approvalId(),
                command.type().equals("APPROVE_VERIFICATION") ? "APPROVE" : "REJECT");
        job.append(
            "VERIFICATION_DECIDED",
            Map.of("approvalId", result.id().toString(), "status", result.status()));
      }
      default -> throw new IllegalArgumentException("Unknown command");
    }
    return snapshot(id);
  }

  public Object skillCatalog() {
    return skills.catalog().snapshot().metadata();
  }
}
