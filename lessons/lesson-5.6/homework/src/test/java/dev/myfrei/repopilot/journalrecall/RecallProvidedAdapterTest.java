package dev.myfrei.repopilot.journalrecall;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecallProvidedAdapterTest {
  static final UUID RUN = UUID.randomUUID();
  @TempDir Path root;

  @Test
  void decoderRejectsCallerJournalHashesAndNonIntegerBudgets() {
    var json = "{\"callIds\":[\"read-1\",\"read-1\"],\"payloadBudgetBytes\":4096}";
    assertEquals(2, RecallRequest.decode(json).callIds().size());
    for (var bad :
        List.of(
            json.replace("4096", "4096.0"),
            json.replace("4096", "\"4096\""),
            json.replace("4096", "0"),
            json.replace("4096", "4097"),
            json.replace(
                "\"payloadBudgetBytes\":4096",
                "\"payloadBudgetBytes\":4096,\"currentHash\":\"fake\""),
            json.replace(
                "\"payloadBudgetBytes\":4096", "\"payloadBudgetBytes\":4096,\"messages\":[]"),
            "{\"callIds\":[null],\"payloadBudgetBytes\":10}"))
      assertThrows(IllegalArgumentException.class, () -> RecallRequest.decode(bad));
  }

  @Test
  void adapterExtractsOnlyMatchedReadResultsAndRefreshesRealFileHashesWithoutLeakingText()
      throws Exception {
    Files.createDirectories(root.resolve("src"));
    var file = root.resolve("src/A.java");
    Files.writeString(file, "class A {}\n");
    var workspace = new WorkspaceService(root);
    var read = workspace.readFile(RUN.toString(), "src/A.java", 1, 1);
    var raw = AgentJson.encode(Map.of("ok", true, "code", "OK", "data", read));
    var journal =
        List.of(
            new AgentMessage("USER", "USER_SECRET", List.of(), List.of()),
            new AgentMessage(
                "ASSISTANT",
                "ASSISTANT_SECRET",
                List.of(
                    new AgentMessage.ToolCall("read-1", "function", "readFile", "ARGUMENT_SECRET"),
                    new AgentMessage.ToolCall("bad", "function", "readFile", "{}"),
                    new AgentMessage.ToolCall("mismatch", "function", "readFile", "{}")),
                List.of()),
            new AgentMessage(
                "TOOL",
                "",
                List.of(),
                List.of(
                    new AgentMessage.ToolReply("read-1", "readFile", raw),
                    new AgentMessage.ToolReply("bad", "readFile", "not-json"),
                    new AgentMessage.ToolReply("orphan", "readFile", raw),
                    new AgentMessage.ToolReply("mismatch", "gitDiff", raw))));
    var policy = mock(ToolRecallPolicy.class);
    when(policy.check(any())).thenReturn(new RecallReport(RUN, List.of(), 0, 4096, false));
    var checker =
        new RecallChecker(
            id -> {
              assertEquals(RUN, id);
              return journal;
            },
            workspace::currentHash,
            policy);
    var request = new RecallRequest(List.of("read-1", "bad", "orphan", "mismatch"), 4096);
    checker.check(RUN, request);
    var captured = ArgumentCaptor.forClass(RecallInput.class);
    verify(policy).check(captured.capture());
    assertEquals(2, captured.getValue().candidates().size());
    var candidate = captured.getValue().candidates().getFirst();
    assertEquals(raw, candidate.resultJson());
    assertEquals(read.fullFileHash(), candidate.currentHash());
    assertTrue(candidate.toolSucceeded());
    assertFalse(captured.getValue().candidates().getLast().toolSucceeded());
    assertEquals("INVALID_RESULT", captured.getValue().candidates().getLast().toolCode());
    String encoded = AgentJson.encode(captured.getValue());
    assertFalse(encoded.contains("USER_SECRET"));
    assertFalse(encoded.contains("ASSISTANT_SECRET"));
    assertFalse(encoded.contains("ARGUMENT_SECRET"));
    Files.writeString(file, "class A { int changed; }\n");
    checker.check(RUN, request);
    verify(policy, times(2)).check(captured.capture());
    assertNotEquals(read.fullFileHash(), captured.getValue().candidates().getFirst().currentHash());
    Files.delete(file);
    checker.check(RUN, request);
    verify(policy, times(3)).check(captured.capture());
    assertNull(captured.getValue().candidates().getFirst().currentHash());
  }

  @Test
  void closedHttpRequestKeepsPreviewBadInputAndTodoDistinct() throws Exception {
    var checker = mock(RecallChecker.class);
    var request = new RecallRequest(List.of("read-1"), 4096);
    when(checker.check(RUN, request))
        .thenReturn(
            new RecallReport(
                RUN,
                List.of(new RecallReport.Check("read-1", "RECALLED", "OK", "{}")),
                2,
                4094,
                true));
    var mvc =
        MockMvcBuilders.standaloneSetup(new RecallController(checker))
            .setControllerAdvice(new RecallExceptionHandler())
            .build();
    var route = "/api/context/runs/" + RUN + "/recall";
    var json = "{\"callIds\":[\"read-1\"],\"payloadBudgetBytes\":4096}";
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checks[0].status").value("RECALLED"));
    mvc.perform(post(route).contentType("application/json").content(json.replace("4096", "4096.0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RECALL_INVALID_REQUEST"));
    when(checker.check(RUN, request)).thenThrow(new UnsupportedOperationException("TODO"));
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("RECALL_NOT_IMPLEMENTED"));
  }
}
