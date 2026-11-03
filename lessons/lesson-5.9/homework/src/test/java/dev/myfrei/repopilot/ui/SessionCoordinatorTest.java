package dev.myfrei.repopilot.ui;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.compaction.ContextRuntime;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.skills.*;
import dev.myfrei.repopilot.verification.*;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;

@Timeout(10)
class SessionCoordinatorTest {
  AgentStore store;
  ResearchRuntime research;
  PlanRuntime plans;
  SkillRuntime skills;
  ControlledService controlled;
  VerificationService verification;
  AtomicInteger calls;

  @TempDir Path root;

  @BeforeEach
  void prepare() throws Exception {
    store = new AgentStore();
    calls = new AtomicInteger();
    var workspace = new WorkspaceService(root);
    Path directory = Files.createDirectories(root.resolve(".agents/skills"));
    ChatModel model =
        prompt ->
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("Answer"),
                        ChatGenerationMetadata.builder().finishReason("stop").build())));
    var engine = new AgentEngine(model, () -> "a".repeat(64), List.of(), store, "fixture", 16384);
    controlled = new ControlledService(engine, workspace, Clock.systemUTC());
    skills = new SkillRuntime(controlled, new SkillCatalog(directory));
    plans = new PlanRuntime(skills, workspace);
    verification =
        new VerificationService(
            workspace,
            store,
            (i, p) -> new VerificationRunner.Result(0, "", "", false, false, "CONTRACT_PASSED"),
            "sha256:" + "a".repeat(64),
            Clock.systemUTC());
    research =
        new ResearchRuntime(
            new VerificationRuntime(new ContextRuntime(plans, store), verification),
            engine,
            store,
            workspace);
  }

  static ResearchRequest task(String question) {
    return ResearchRequest.decode(
        AgentJson.encode(
            Map.of(
                "question",
                question,
                "mode",
                "ASK",
                "maxInputTokens",
                16000,
                "steps",
                List.of(
                    Map.of(
                        "id",
                        "code",
                        "title",
                        "Read code",
                        "criterion",
                        "READ_CODE",
                        "path",
                        "src/main/java/Example.java",
                        "startLine",
                        1,
                        "endLine",
                        1,
                        "dependsOn",
                        List.of(),
                        "required",
                        true)))));
  }

  SessionCoordinator coordinator(
      SessionCoordinator.Dispatcher dispatcher, int concurrency, int events) {
    return new SessionCoordinator(
        dispatcher, research, store, plans, skills, controlled, verification, concurrency, events);
  }

  ResearchRuntime.ResearchRunResult dispatch(
      UUID conversation, ResearchRequest request, java.util.function.BooleanSupplier cancelled) {
    calls.incrementAndGet();
    ChatModel model =
        prompt ->
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("Actual completed answer"),
                        ChatGenerationMetadata.builder().finishReason("stop").build())));
    var engine =
        new AgentEngine(model, () -> "a".repeat(64), List.of(), store, "fixture", 16384)
            .withRunControl(
                new ResearchBudget(ResearchLimits.DEFAULT, cancelled).control(false, id -> {}));
    var run = engine.run(conversation, request.task().task().question());
    return new ResearchRuntime.ResearchRunResult(
        run, List.of(), null, null, verification.state(conversation), null);
  }

  @Test
  void sameRequestIsIdempotentAndChangedTaskConflictsWithoutSecondDispatch() throws Exception {
    var c = coordinator(this::dispatch, 1, 128);
    UUID conversation = store.createConversation().id(), request = UUID.randomUUID();
    var initial = c.submit(conversation, request, task("Q"));
    await(c, initial.id());
    var replay = c.submit(conversation, request, task("Q"));
    assertThat(replay.id()).isEqualTo(initial.id());
    assertThat(calls).hasValue(1);
    assertCode(
        "SESSION_REQUEST_CONFLICT", () -> c.submit(conversation, request, task("Different")));
    assertThat(calls).hasValue(1);
  }

  @Test
  void eventPagesReplayOrderedFinalEventAndGetNeverDispatches() throws Exception {
    var c = coordinator(this::dispatch, 1, 128);
    var job =
        c.submit(store.createConversation().id(), UUID.randomUUID(), task("PRIVATE-QUESTION"));
    await(c, job.id());
    var page = c.events(job.id(), 0, 0);
    assertThat(page.terminal()).isTrue();
    assertThat(page.events().getLast().type()).isEqualTo("SESSION_FINISHED");
    for (int i = 0; i < page.events().size(); i++)
      assertThat(page.events().get(i).sequence()).isEqualTo(i + 1);
    assertThat(AgentJson.encode(page))
        .doesNotContain("PRIVATE-QUESTION", "Actual completed answer");
    assertThat(c.events(job.id(), page.nextSequence(), 0).events()).isEmpty();
    assertThat(c.snapshot(job.id()).result().run().answer()).isEqualTo("Actual completed answer");
    assertThat(calls).hasValue(1);
  }

  @Test
  void overflowRequiresSnapshotRecoveryAndFutureCursorIsInvalid() throws Exception {
    var c = coordinator(this::dispatch, 1, 2);
    var job = c.submit(store.createConversation().id(), UUID.randomUUID(), task("Q"));
    await(c, job.id());
    assertCode("SESSION_EVENTS_EXPIRED", () -> c.events(job.id(), 0, 0));
    var snapshot = c.snapshot(job.id());
    assertThat(c.events(job.id(), snapshot.lastSequence(), 0).events()).isEmpty();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> c.events(job.id(), snapshot.lastSequence() + 1, 0));
    assertThat(calls).hasValue(1);
  }

  @Test
  void queuedCancelNeverCallsDispatcherOrModelAndDoesNotCancelAnotherJob() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var c =
        coordinator(
            (conversation, request, cancelled) -> {
              entered.countDown();
              try {
                release.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              return dispatch(conversation, request, cancelled);
            },
            1,
            128);
    var first = c.submit(store.createConversation().id(), UUID.randomUUID(), task("First"));
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    var second = c.submit(store.createConversation().id(), UUID.randomUUID(), task("Second"));
    c.command(second.id(), new SessionCommand("CANCEL", null));
    await(c, second.id());
    assertThat(c.snapshot(second.id()).stopReason()).isEqualTo("CANCELLED_BEFORE_START");
    assertThat(c.snapshot(second.id()).runId()).isNull();
    assertThat(calls).hasValue(0);
    release.countDown();
    await(c, first.id());
    assertThat(calls).hasValue(1);
  }

  @Test
  void legacyRunWinningRaceNeverBindsOrCancelsItsId() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    UUID conversation = store.createConversation().id();
    ChatModel model =
        prompt -> {
          entered.countDown();
          try {
            release.await(3, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          return new ChatResponse(
              List.of(
                  new Generation(
                      new AssistantMessage("Legacy"),
                      ChatGenerationMetadata.builder().finishReason("stop").build())));
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var old =
          executor.submit(
              () ->
                  new AgentEngine(model, () -> "a".repeat(64), List.of(), store, "fixture", 4096)
                      .run(conversation, "Old"));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      UUID legacy = store.conversation(conversation).activeRunId();
      var c = coordinator(this::dispatch, 1, 128);
      var job = c.submit(conversation, UUID.randomUUID(), task("New"));
      await(c, job.id());
      assertThat(c.snapshot(job.id()).status()).isEqualTo("FAILED");
      assertThat(c.snapshot(job.id()).stopReason()).isEqualTo("AGENT_BUSY");
      assertThat(c.snapshot(job.id()).runId()).isNull();
      c.command(job.id(), new SessionCommand("CANCEL", null));
      assertThat(store.conversation(conversation).activeRunId()).isEqualTo(legacy);
      release.countDown();
      assertThat(old.get(2, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
    } finally {
      release.countDown();
    }
  }

  @Test
  void observerFailureCannotLeakStoreRunOwnershipAndChildStoreDoesNotNotifyParent() {
    var notifications = new AtomicInteger();
    UUID conversation = store.createConversation().id();
    try (var observer =
        store.observeRun(
            id -> {
              notifications.incrementAndGet();
              throw new IllegalStateException();
            },
            event -> {
              throw new IllegalStateException();
            })) {
      dispatch(conversation, task("Q"), () -> false);
      var child = new AgentStore();
      child.createConversation();
    }
    assertThat(store.conversation(conversation).activeRunId()).isNull();
    assertThat(notifications).hasValue(1);
  }

  @Test
  void newJobCanStartImmediatelyAfterTerminalPublication() throws Exception {
    var c = coordinator(this::dispatch, 1, 128);
    UUID conversation = store.createConversation().id();
    for (int i = 0; i < 20; i++) {
      var job = c.submit(conversation, UUID.randomUUID(), task("Q"));
      await(c, job.id());
    }
    assertThat(calls).hasValue(20);
  }

  @Test
  void externalCancellationFlagClosesHandoffBeforeModelAdmission() {
    var flag = new AtomicBoolean();
    var budget = new ResearchBudget(ResearchLimits.DEFAULT, flag::get);
    var control = budget.control(false, id -> {});
    flag.set(true);
    assertThat(control.check(UUID.randomUUID())).isEqualTo("CANCELLED");
    assertThat(control.beforeModel(UUID.randomUUID(), 100, 384)).isEqualTo("CANCELLED");
    assertThat(budget.snapshot().admittedModelCalls()).isZero();
  }

  static void await(SessionCoordinator c, UUID id) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (System.nanoTime() < end) {
      if (Set.of("COMPLETED", "STOPPED", "FAILED").contains(c.snapshot(id).status())) return;
      Thread.sleep(5);
    }
    throw new AssertionError("Session not finished");
  }

  static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(SessionProblem.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
