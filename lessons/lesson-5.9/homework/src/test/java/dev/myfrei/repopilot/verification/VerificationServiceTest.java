package dev.myfrei.repopilot.verification;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class VerificationServiceTest {
  @TempDir Path root;
  AgentStore store;
  WorkspaceService workspace;
  VerificationService service;
  Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
  String image = "sha256:" + "a".repeat(64);
  UUID conversation;
  AtomicInteger executions;
  AtomicReference<VerificationRunner.Result> outcome;

  @BeforeEach
  void prepare() throws Exception {
    Path file = root.resolve(VerificationService.SOURCE);
    Files.createDirectories(file.getParent());
    Files.copy(Path.of("workspace-fixture").resolve(VerificationService.SOURCE), file);
    store = new AgentStore();
    conversation = store.createConversation().id();
    workspace = new WorkspaceService(root);
    executions = new AtomicInteger();
    outcome =
        new AtomicReference<>(
            new VerificationRunner.Result(
                0, "fixed checks passed", "", false, false, "CONTRACT_PASSED"));
    service =
        new VerificationService(
            workspace,
            store,
            (id, snapshot) -> {
              executions.incrementAndGet();
              assertThat(id).isEqualTo(image);
              assertThat(Files.exists(snapshot.resolve("pom.xml"))).isFalse();
              try {
                assertThat(Files.readAllBytes(snapshot.resolve(VerificationService.SOURCE)))
                    .isEqualTo(workspace.snapshotBytes(VerificationService.SOURCE));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return outcome.get();
            },
            image,
            clock);
  }

  @Test
  void pendingRequestDoesNothingAndOneManualApprovalCreatesImmutableReport() {
    var request = service.request(conversation, VerificationService.COMMAND);
    assertThat(request.status()).isEqualTo("PENDING");
    assertThat(executions).hasValue(0);
    assertThat(service.decide(request.id(), "APPROVE").status()).isEqualTo("PASS");
    assertThat(executions).hasValue(1);
    var report = service.reports(conversation).getFirst();
    assertThat(report.inputRevision()).isEqualTo(request.inputRevision());
    assertThat(report.imageId()).isEqualTo(image);
    assertThat(report.inputsStillCurrent()).isTrue();
    assertThat(report.exitCode()).isZero();
    assertThatThrownBy(() -> service.reports(conversation).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertCode("VERIFICATION_NOT_PENDING", () -> service.decide(request.id(), "APPROVE"));
  }

  @Test
  void rejectUnknownCommandAndForgedImageNeverLaunchRunner() {
    assertCode("VERIFICATION_COMMAND_DENIED", () -> service.request(conversation, "mvn test"));
    var request = service.request(conversation, VerificationService.COMMAND);
    assertThat(service.decide(request.id(), "REJECT").status()).isEqualTo("REJECTED");
    assertCode("VERIFICATION_NOT_PENDING", () -> service.decide(request.id(), "APPROVE"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new VerificationService(
                    workspace, store, (i, p) -> outcome.get(), "mutable:latest", clock));
    assertThat(executions).hasValue(0);
  }

  @Test
  void staleApprovalAndTtlBoundaryAreRejectedBeforeExecution() throws Exception {
    var request = service.request(conversation, VerificationService.COMMAND);
    Files.writeString(root.resolve(VerificationService.SOURCE), "changed\n");
    assertCode("VERIFICATION_STALE", () -> service.decide(request.id(), "APPROVE"));
    assertThat(service.request(request.id()).status()).isEqualTo("STALE");
    var expired =
        new VerificationService(
            workspace,
            store,
            (i, p) -> outcome.get(),
            image,
            Clock.fixed(clock.instant(), ZoneOffset.UTC));
    var r = expired.request(conversation, VerificationService.COMMAND);
    var mutable = new MutableClock(r.expiresAt());
    var timed = new VerificationService(workspace, store, (i, p) -> outcome.get(), image, mutable);
    var t = timed.request(conversation, VerificationService.COMMAND);
    mutable.now = t.expiresAt();
    assertCode("VERIFICATION_EXPIRED", () -> timed.decide(t.id(), "APPROVE"));
    assertThat(executions).hasValue(0);
  }

  @Test
  void failAndTimeoutAndUnknownExitAreDistinctAndNeverTurnIntoPass() {
    for (var result :
        List.of(
            new VerificationRunner.Result(10, "failure", "", false, false, "CONTRACT_FAILED"),
            new VerificationRunner.Result(null, "partial", "", true, true, "RUN_TIMEOUT"),
            new VerificationRunner.Result(137, "", "", false, false, "MEMORY_LIMIT"))) {
      var own = new VerificationService(workspace, store, (i, p) -> result, image, clock);
      var r = own.request(conversation, VerificationService.COMMAND);
      String expected =
          result.timedOut()
              ? "TIMEOUT"
              : Integer.valueOf(10).equals(result.exitCode()) ? "FAIL" : "ERROR";
      assertThat(own.decide(r.id(), "APPROVE").status()).isEqualTo(expected);
    }
  }

  @Test
  void physicalChangeDuringExecutionMarksHistoricalReportStale() throws Exception {
    var own =
        new VerificationService(
            workspace,
            store,
            (i, p) -> {
              try {
                Files.writeString(root.resolve(VerificationService.SOURCE), "human change\n");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return outcome.get();
            },
            image,
            clock);
    var request = own.request(conversation, VerificationService.COMMAND);
    assertThat(own.decide(request.id(), "APPROVE").status()).isEqualTo("STALE");
    assertThat(own.reports(conversation).getFirst().exitCode()).isZero();
    assertThat(own.reports(conversation).getFirst().inputsStillCurrent()).isFalse();
  }

  @Test
  void secondPassAllowedButThirdAttemptDeniedWithoutExecution() {
    for (int i = 0; i < 2; i++)
      service.decide(service.request(conversation, VerificationService.COMMAND).id(), "APPROVE");
    assertCode(
        "VERIFICATION_ATTEMPT_LIMIT",
        () -> service.request(conversation, VerificationService.COMMAND));
    assertThat(executions).hasValue(2);
  }

  @Test
  void repeatedFailureStopsAsNoProgressAndNewRevisionGetsNewAllowance() throws Exception {
    outcome.set(
        new VerificationRunner.Result(10, "same failure", "", false, false, "CONTRACT_FAILED"));
    for (int i = 0; i < 2; i++)
      service.decide(service.request(conversation, VerificationService.COMMAND).id(), "APPROVE");
    assertCode(
        "VERIFICATION_NO_PROGRESS",
        () -> service.request(conversation, VerificationService.COMMAND));
    Files.writeString(root.resolve(VerificationService.SOURCE), "// new version\n");
    assertThat(service.request(conversation, VerificationService.COMMAND).status())
        .isEqualTo("PENDING");
    assertThat(executions).hasValue(2);
  }

  @Test
  void reportToolIsPureDataScopedAndRechecksLiveRevision() throws Exception {
    var r = service.request(conversation, VerificationService.COMMAND);
    service.decide(r.id(), "APPROVE");
    var report = service.reports(conversation).getFirst();
    var tool = service.reportTools().getFirst();
    String args = AgentJson.encode(Map.of("reportId", report.id().toString()));
    assertThat(tool.execute(args, new AgentToolContext(UUID.randomUUID(), conversation)).content())
        .contains("PASS", "\"inputsStillCurrent\":true");
    Files.writeString(root.resolve(VerificationService.SOURCE), "changed\n");
    assertThat(tool.execute(args, new AgentToolContext(UUID.randomUUID(), conversation)).content())
        .contains("\"inputsStillCurrent\":false");
    assertThat(report.inputsStillCurrent()).isTrue();
    assertThat(tool.execute(args, new AgentToolContext(UUID.randomUUID(), UUID.randomUUID())).ok())
        .isFalse();
    assertThat(executions).hasValue(1);
  }

  @Test
  void activeVerificationRejectsConcurrentApprovalAndAlwaysReleasesBusyState() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var own =
        new VerificationService(
            workspace,
            store,
            (i, p) -> {
              entered.countDown();
              try {
                release.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              return outcome.get();
            },
            image,
            clock);
    var first = own.request(conversation, VerificationService.COMMAND);
    var second = own.request(conversation, VerificationService.COMMAND);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running = executor.submit(() -> own.decide(first.id(), "APPROVE"));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(own.request(first.id()).status()).isEqualTo("RUNNING");
      assertCode("VERIFICATION_BUSY", () -> own.decide(second.id(), "APPROVE"));
      release.countDown();
      assertThat(running.get(3, TimeUnit.SECONDS).status()).isEqualTo("PASS");
      assertThat(own.decide(second.id(), "APPROVE").status()).isEqualTo("PASS");
    } finally {
      release.countDown();
    }
  }

  static void assertCode(
      String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            VerificationProblem.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  static class MutableClock extends Clock {
    Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
