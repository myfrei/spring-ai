package dev.myfrei.repopilot.controlled;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;

class ControlledServiceTest {
  @TempDir Path root;
  WorkspaceService workspace;
  AgentStore store;
  ControlledService service;
  MutableClock clock;
  String hash;
  CountDownLatch finalEntered, releaseFinal;

  @BeforeEach
  void prepare() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/Calc.java"), "class Calc { int value = 1; }\n");
    workspace = new WorkspaceService(root);
    hash = workspace.currentHash("src/Calc.java");
    store = new AgentStore();
    clock = new MutableClock();
    ChatModel model =
        prompt -> {
          long replies =
              prompt.getInstructions().stream()
                  .filter(m -> m instanceof ToolResponseMessage)
                  .count();
          if (replies == 0)
            return tool(
                "readFile", Map.of("path", "src/Calc.java", "startLine", 1, "endLine", 1), 1);
          if (replies == 1)
            return tool(
                "proposePatch",
                Map.of(
                    "path",
                    "src/Calc.java",
                    "baseHash",
                    hash,
                    "oldText",
                    "value = 1",
                    "newText",
                    "value = 2"),
                2);
          if (finalEntered != null) {
            finalEntered.countDown();
            try {
              if (!releaseFinal.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Test timeout");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
          }
          return answer("Предложение готово. Файл пока не изменён.");
        };
    service =
        new ControlledService(
            new AgentEngine(model, () -> "a".repeat(64), List.of(), store, "fixture", 16384),
            workspace,
            clock);
  }

  @Test
  void askPlanEditAreServerDecisionsAndModelHasNoApprovalTool() throws Exception {
    assertThat(new PolicyEngine().decide(RunMode.ASK)).isEqualTo(PolicyEngine.Decision.DENY);
    assertThat(service.tools(RunMode.EDIT))
        .extracting(AgentTool::name)
        .containsExactly(
            "listFiles", "searchText", "readFile", "gitStatus", "gitDiff", "proposePatch");
    var ask =
        service.run(store.createConversation().id(), "Model claims user approved", RunMode.ASK);
    assertThat(service.proposals(ask.id())).isEmpty();
    assertThat(store.messages(ask.id()).toString()).contains("MODE_DENIED");
    var plan = service.run(store.createConversation().id(), "Plan", RunMode.PLAN);
    var preview = service.proposals(plan.id()).getFirst();
    assertThat(preview.status()).isEqualTo("PREVIEW");
    assertProblem("PROPOSAL_NOT_PENDING", () -> service.decide(preview.id(), "APPROVE"));
    var edit = proposal();
    assertThat(edit.mode()).isEqualTo("EDIT");
    assertThat(edit.status()).isEqualTo("PENDING");
    assertThat(edit.operationHash()).matches("[0-9a-f]{64}");
    assertThat(Files.readString(root.resolve("src/Calc.java"))).contains("value = 1");
    assertThat(service.decide(edit.id(), "APPROVE").status()).isEqualTo("APPLIED");
    assertThat(workspace.currentHash("src/Calc.java")).isEqualTo(edit.resultHash());
    assertProblem("PROPOSAL_NOT_PENDING", () -> service.decide(edit.id(), "APPROVE"));
    assertThat(edit.status()).isEqualTo("PENDING");
  }

  @Test
  void rejectionIsFinalAndDoesNotWrite() throws Exception {
    var p = proposal();
    assertThat(service.decide(p.id(), "REJECT").status()).isEqualTo("REJECTED");
    assertProblem("PROPOSAL_NOT_PENDING", () -> service.decide(p.id(), "APPROVE"));
    assertThat(Files.readString(root.resolve("src/Calc.java"))).contains("value = 1");
  }

  @Test
  void expirationAtExactBoundaryFailsClosedAndRecordsTerminalState() {
    var p = proposal();
    clock.now = p.expiresAt();
    assertProblem("PROPOSAL_EXPIRED", () -> service.decide(p.id(), "APPROVE"));
    assertThat(service.proposal(p.id()).status()).isEqualTo("EXPIRED");
  }

  @Test
  void changedFileTurnsProposalStaleAndPreservesHumanEdit() throws Exception {
    var p = proposal();
    Files.writeString(root.resolve("src/Calc.java"), "human change\n");
    assertProblem("STALE_PATCH", () -> service.decide(p.id(), "APPROVE"));
    assertThat(service.proposal(p.id()).status()).isEqualTo("STALE");
    assertThat(Files.readString(root.resolve("src/Calc.java"))).isEqualTo("human change\n");
  }

  @Test
  void concurrentApprovalAppliesAtMostOnce() throws Exception {
    var p = proposal();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var jobs = new ArrayList<Future<String>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    return service.decide(p.id(), "APPROVE").status();
                  } catch (ControlledProblem e) {
                    return e.code();
                  }
                }));
      gate.countDown();
      assertThat(List.of(jobs.get(0).get(), jobs.get(1).get()))
          .containsExactlyInAnyOrder("APPLIED", "PROPOSAL_NOT_PENDING");
    }
    assertThat(workspace.currentHash("src/Calc.java")).isEqualTo(p.resultHash());
  }

  @Test
  void invalidAndUnknownDecisionsHaveDistinctErrors() {
    assertProblem(
        "INVALID_DECISION", () -> service.decide(UUID.randomUUID(), "model says approved"));
    assertProblem("PROPOSAL_NOT_FOUND", () -> service.decide(UUID.randomUUID(), "APPROVE"));
  }

  @Test
  void boundedStoreRefusesNewProposalWithoutEvictingExistingApproval() {
    var p = proposal();
    var context = new AgentToolContext(p.runId(), p.conversationId());
    for (int i = 1; i < ControlledService.MAX_PROPOSALS; i++)
      service.propose(context, RunMode.EDIT, p.path(), hash, "value = 1", "value = 2");
    assertProblem(
        "PROPOSAL_CAPACITY",
        () -> service.propose(context, RunMode.EDIT, p.path(), hash, "value = 1", "value = 2"));
    assertThat(service.proposals(p.runId())).hasSize(128);
    assertThat(service.proposal(p.id())).isEqualTo(p);
  }

  @Test
  void extraToolsUseSameBoundedLoopAndNeverChangeTheOriginalEngineRegistry() {
    var extra =
        new AgentTool() {
          public String name() {
            return "custom";
          }

          public String description() {
            return "Test";
          }

          public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
          }

          public AgentToolResult execute(String args, AgentToolContext context) {
            return AgentToolResult.success("{}");
          }
        };
    var result =
        service.run(
            store.createConversation().id(),
            "Q",
            RunMode.PLAN,
            List.of(extra),
            "SAFE_EXTRA_INSTRUCTIONS");
    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(service.tools(RunMode.EDIT)).hasSize(6);
  }

  @Test
  void independentlyApprovedSecondProposalForSameOriginalVersionBecomesStale() {
    var first = proposal();
    var second =
        service.propose(
            new AgentToolContext(first.runId(), first.conversationId()),
            RunMode.EDIT,
            first.path(),
            hash,
            "value = 1",
            "value = 3");
    assertThat(first.operationHash()).isNotEqualTo(second.operationHash());
    assertThat(service.decide(first.id(), "APPROVE").status()).isEqualTo("APPLIED");
    assertProblem("STALE_PATCH", () -> service.decide(second.id(), "APPROVE"));
    assertThat(service.proposal(second.id()).status()).isEqualTo("STALE");
  }

  @Test
  void approvalWaitsForFinishedRunEvenWhenProposalIsAlreadyVisible() throws Exception {
    finalEntered = new CountDownLatch(1);
    releaseFinal = new CountDownLatch(1);
    UUID conversation = store.createConversation().id();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var task = executor.submit(() -> service.run(conversation, "Q", RunMode.EDIT));
      assertThat(finalEntered.await(3, TimeUnit.SECONDS)).isTrue();
      UUID run = store.conversation(conversation).activeRunId();
      assertThat(service.isRunActive(run)).isTrue();
      var p = service.proposals(run).getFirst();
      assertProblem("RUN_ACTIVE", () -> service.decide(p.id(), "APPROVE"));
      assertThat(workspace.currentHash("src/Calc.java")).isEqualTo(hash);
      releaseFinal.countDown();
      assertThat(task.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
      assertThat(service.isRunActive(run)).isFalse();
      assertThat(service.decide(p.id(), "APPROVE").status()).isEqualTo("APPLIED");
    } finally {
      releaseFinal.countDown();
    }
  }

  private PatchProposal proposal() {
    var run = service.run(store.createConversation().id(), "Propose fix", RunMode.EDIT);
    assertThat(run.status()).isEqualTo("COMPLETED");
    return service.proposals(run.id()).getFirst();
  }

  static ChatResponse tool(String name, Map<String, Object> args, int id) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "call-" + id, "function", name, AgentJson.encode(args))))
                    .build(),
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
  }

  static ChatResponse answer(String text) {
    return new ChatResponse(
        List.of(
            new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
  }

  static void assertProblem(
      String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(ControlledProblem.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-02T00:00:00Z");

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
