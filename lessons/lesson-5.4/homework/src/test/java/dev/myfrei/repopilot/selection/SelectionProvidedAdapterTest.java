package dev.myfrei.repopilot.selection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.skills.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SelectionProvidedAdapterTest {
  static final String V = "a".repeat(64);
  @TempDir Path root;

  @Test
  void closedDecoderRejectsCallerCostsVersionsAndNonIntegerBudgets() {
    var json =
        "{\"skills\":[{\"name\":\"review\",\"version\":\"" + V + "\"}],\"budgetBytes\":4096}";
    assertEquals(4096, SelectionRequest.decode(json).budgetBytes());
    for (var bad :
        List.of(
            json.replace("4096", "4096.0"),
            json.replace("4096", "\"4096\""),
            json.replace("4096", "0"),
            json.replace("4096", "4097"),
            json.replace("\"name\":\"review\"", "\"name\":\"review\",\"activationBytes\":1"),
            json.replace("\"budgetBytes\":4096", "\"budgetBytes\":4096,\"catalog\":[]"),
            json.replace(V, "INVALID")))
      assertThrows(IllegalArgumentException.class, () -> SelectionRequest.decode(bad));
  }

  @Test
  void adapterUsesFreshRealCatalogAndExactServerEnvelopeCosts() throws Exception {
    var dir = Files.createDirectory(root.resolve("review"));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: review\ndescription: Review Java changes\n---\n# Review\nПрочитай \"код\".\n");
    var catalog = new SkillCatalog(root);
    var snapshot = catalog.snapshot();
    var version = snapshot.metadata().getFirst().version();
    var policy = mock(SkillSelectionPolicy.class);
    var report = new SelectionReport(List.of(), List.of(), 0, 4096, false);
    when(policy.check(any())).thenReturn(report);
    var checker = new SelectionChecker(catalog, policy);
    var request = new SelectionRequest(List.of(new SelectionInput.Pin("review", version)), 4096);
    assertSame(report, checker.check(request));
    var input = ArgumentCaptor.forClass(SelectionInput.class);
    verify(policy).check(input.capture());
    assertEquals(
        snapshot.activationBytes("review"),
        input.getValue().catalog().getFirst().activationBytes());
    assertEquals(version, input.getValue().catalog().getFirst().version());
    Files.writeString(
        dir.resolve("SKILL.md"), Files.readString(dir.resolve("SKILL.md")) + "Ещё шаг.\n");
    checker.check(request);
    verify(policy, times(2)).check(input.capture());
    assertNotEquals(version, input.getValue().catalog().getFirst().version());
    assertEquals(version, input.getValue().requested().getFirst().version());
  }

  @Test
  void httpShowsPreviewAndDistinguishesInvalidInputFromTodo() throws Exception {
    var checker = mock(SelectionChecker.class);
    var request = new SelectionRequest(List.of(new SelectionInput.Pin("review", V)), 4096);
    when(checker.check(request))
        .thenReturn(
            new SelectionReport(
                List.of(new SelectionReport.Check("review", V, "SELECTED")),
                request.skills(),
                100,
                3996,
                true));
    var mvc =
        MockMvcBuilders.standaloneSetup(new SelectionController(checker))
            .setControllerAdvice(new SelectionExceptionHandler())
            .build();
    var json =
        "{\"skills\":[{\"name\":\"review\",\"version\":\"" + V + "\"}],\"budgetBytes\":4096}";
    mvc.perform(post("/api/skills/selection/check").contentType("application/json").content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allSelected").value(true));
    mvc.perform(
            post("/api/skills/selection/check")
                .contentType("application/json")
                .content(json.replace("4096", "\"4096\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SELECTION_INVALID_REQUEST"));
    when(checker.check(request)).thenThrow(new UnsupportedOperationException("TODO"));
    mvc.perform(post("/api/skills/selection/check").contentType("application/json").content(json))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("SELECTION_NOT_IMPLEMENTED"));
  }
}
