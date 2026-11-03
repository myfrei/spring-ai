package dev.myfrei.repopilot.researchplan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ResearchReadPlanProvidedAdapterTest {
  static final UUID PARENT = UUID.randomUUID();
  static final String H = "a".repeat(64);
  @TempDir Path root;

  @Test
  void closedDecoderAndDtoCapsRejectForgedEvidenceAndOversizedBatches() {
    assertEquals(160, ResearchReadPlanRequest.decode("{\"readLineBudget\":160}").readLineBudget());
    for (var bad :
        List.of(
            "{\"readLineBudget\":160.0}",
            "{\"readLineBudget\":0}",
            "{\"readLineBudget\":161}",
            "{\"readLineBudget\":10,\"findings\":[]}",
            "{\"readLineBudget\":10,\"parentReads\":[]}",
            "{}"))
      assertThrows(IllegalArgumentException.class, () -> ResearchReadPlanRequest.decode(bad));
    var span = new ResearchCheckPlanInput.ParentRead(PARENT, "src/A.java", H, 1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResearchCheckPlanInput(
                PARENT, List.of(), Collections.nCopies(9, span), Map.of(), 1));
    var task = new ResearchCheckPlanReport.ReadTask("src/A.java", H, 1, 1);
    assertThrows(
        ResearchReadPlanProblem.class,
        () ->
            new ResearchCheckPlanReport(
                PARENT, List.of(), Collections.nCopies(17, task), 0, 0, 0, false));
  }

  @Test
  void adapterUsesAcceptedFindingsPairedParentMetadataAndFreshHashesWithoutLeakingText()
      throws Exception {
    Files.createDirectories(root.resolve("src"));
    var file = root.resolve("src/A.java");
    Files.writeString(
        file, String.join("\n", Collections.nCopies(20, "FILE_CONTENT_SECRET")) + "\n");
    var workspace = new WorkspaceService(root);
    var read = workspace.readFile(PARENT.toString(), "src/A.java", 1, 4);
    var before = workspace.receipts(PARENT.toString());
    var raw = AgentJson.encode(Map.of("ok", true, "code", "OK", "data", read));
    var finding =
        new ResearchFinding("CHILD_STATEMENT_SECRET", "src/A.java", read.fullFileHash(), 1, 10);
    var count = new AtomicInteger();
    var budget =
        new ResearchBudgetSnapshot(ResearchLimits.DEFAULT, 0, 0, 0, 0, null, null, false, 1, 0);
    java.util.function.Function<UUID, ResearchSnapshot> snapshot =
        id -> {
          assertEquals(PARENT, id);
          return new ResearchSnapshot(
              PARENT,
              "COMPLETED",
              false,
              budget,
              List.of(
                  new ResearchChildReport(
                      UUID.randomUUID(),
                      count.getAndIncrement() < 3 ? "CANDIDATE" : "REPORT_INVALID",
                      List.of(finding),
                      List.of("UNCERTAINTY_SECRET"))));
        };
    var journal =
        List.of(
            new AgentMessage("USER", "USER_SECRET", List.of(), List.of()),
            new AgentMessage(
                "ASSISTANT",
                "ASSISTANT_SECRET",
                List.of(
                    new AgentMessage.ToolCall("good", "function", "readFile", "ARGUMENT_SECRET"),
                    new AgentMessage.ToolCall("bad", "function", "readFile", "{}"),
                    new AgentMessage.ToolCall("wrong", "function", "readFile", "{}")),
                List.of()),
            new AgentMessage(
                "TOOL",
                "",
                List.of(),
                List.of(
                    new AgentMessage.ToolReply("good", "readFile", raw),
                    new AgentMessage.ToolReply("bad", "readFile", "not-json"),
                    new AgentMessage.ToolReply("wrong", "gitDiff", raw),
                    new AgentMessage.ToolReply("orphan", "readFile", raw))));
    var policy = mock(ResearchCheckPlanPolicy.class);
    when(policy.check(any()))
        .thenReturn(new ResearchCheckPlanReport(PARENT, List.of(), List.of(), 0, 0, 0, true));
    var checker =
        new ResearchReadPlanChecker(
            snapshot,
            id -> {
              assertEquals(PARENT, id);
              return journal;
            },
            workspace::currentHash,
            policy);
    checker.check(PARENT, new ResearchReadPlanRequest(100));
    var input = ArgumentCaptor.forClass(ResearchCheckPlanInput.class);
    verify(policy).check(input.capture());
    assertEquals(1, input.getValue().findings().size());
    assertEquals(1, input.getValue().parentReads().size());
    assertEquals(4, input.getValue().parentReads().getFirst().endLine());
    assertEquals(read.fullFileHash(), input.getValue().currentHashes().get("src/A.java"));
    var encoded = AgentJson.encode(input.getValue());
    for (var secret :
        List.of(
            "USER_SECRET",
            "ASSISTANT_SECRET",
            "ARGUMENT_SECRET",
            "FILE_CONTENT_SECRET",
            "CHILD_STATEMENT_SECRET",
            "UNCERTAINTY_SECRET")) assertFalse(encoded.contains(secret));
    Files.writeString(file, "changed\n");
    checker.check(PARENT, new ResearchReadPlanRequest(100));
    verify(policy, times(2)).check(input.capture());
    assertNotEquals(read.fullFileHash(), input.getValue().currentHashes().get("src/A.java"));
    Files.delete(file);
    checker.check(PARENT, new ResearchReadPlanRequest(100));
    verify(policy, times(3)).check(input.capture());
    assertTrue(input.getValue().currentHashes().isEmpty());
    checker.check(PARENT, new ResearchReadPlanRequest(100));
    verify(policy, times(4)).check(input.capture());
    assertTrue(input.getValue().findings().isEmpty());
    assertEquals(before, workspace.receipts(PARENT.toString()));
  }

  @Test
  void httpSeparatesPreviewForgedInputUnknownResearchAndTodo() throws Exception {
    var checker = mock(ResearchReadPlanChecker.class);
    var request = new ResearchReadPlanRequest(80);
    var route = "/api/research/runs/" + PARENT + "/read-plan";
    var json = "{\"readLineBudget\":80}";
    var mvc =
        MockMvcBuilders.standaloneSetup(new ResearchReadPlanController(checker))
            .setControllerAdvice(new ResearchReadPlanExceptionHandler())
            .build();
    when(checker.check(PARENT, request))
        .thenReturn(new ResearchCheckPlanReport(PARENT, List.of(), List.of(), 0, 0, 0, true));
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true));
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content(json.replace("}", ",\"currentHashes\":{}}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("READ_PLAN_INVALID_REQUEST"));
    when(checker.check(PARENT, request)).thenThrow(new ResearchException("RESEARCH_RUN_NOT_FOUND"));
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isNotFound());
    doThrow(new ResearchReadPlanProblem("READ_PLAN_EVIDENCE_LIMIT"))
        .when(checker)
        .check(PARENT, request);
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("READ_PLAN_EVIDENCE_LIMIT"));
    doThrow(new UnsupportedOperationException("TODO")).when(checker).check(PARENT, request);
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("READ_PLAN_NOT_IMPLEMENTED"));
  }
}
