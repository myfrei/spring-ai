package dev.myfrei.repopilot.frontier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.plan.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrontierProvidedAdapterTest {
  static final UUID RUN = UUID.randomUUID();

  static PlanSnapshot snapshot(boolean current) {
    return new PlanSnapshot(
        RUN,
        9,
        List.of(
            new PlanSnapshot.StepView(
                "read",
                "Read code",
                "READ_CODE",
                "src/A.java",
                2,
                3,
                List.of(),
                true,
                "COMPLETED",
                "a".repeat(64),
                current)));
  }

  @Test
  void adapterObtainsFreshServerSnapshotAndOnlyCallerLimits() {
    var calls = new AtomicInteger();
    var policy = mock(PlanFrontierPolicy.class);
    when(policy.check(any())).thenReturn(new FrontierReport(RUN, 9, List.of(), List.of(), 0, 30));
    var checker =
        new FrontierChecker(
            id -> {
              assertEquals(RUN, id);
              return snapshot(calls.getAndIncrement() == 0);
            },
            policy);
    checker.check(RUN, new FrontierRequest(2, 30));
    checker.check(RUN, new FrontierRequest(1, 10));
    var input = ArgumentCaptor.forClass(FrontierInput.class);
    verify(policy, times(2)).check(input.capture());
    assertTrue(input.getAllValues().getFirst().steps().getFirst().evidenceCurrent());
    assertFalse(input.getValue().steps().getFirst().evidenceCurrent());
    assertEquals(9, input.getValue().revision());
    assertEquals(1, input.getValue().maxSteps());
    assertEquals(10, input.getValue().readLineBudget());
    assertEquals(2, input.getValue().steps().getFirst().startLine());
  }

  @Test
  void providedInputGuardsRejectUnknownForwardDuplicateAndOversizedGraphs() {
    var a = new FrontierInput.Step("a", "PENDING", false, 1, 1, List.of(), true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrontierInput(
                RUN,
                0,
                List.of(
                    new FrontierInput.Step("b", "COMPLETED", true, 1, 1, List.of("missing"), true)),
                1,
                1));
    assertThrows(
        IllegalArgumentException.class, () -> new FrontierInput(RUN, 0, List.of(a, a), 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrontierInput(
                RUN,
                0,
                List.of(
                    a,
                    new FrontierInput.Step("b", "ACTIVE", false, 1, 1, List.of("c"), true),
                    new FrontierInput.Step("c", "PENDING", false, 1, 1, List.of(), true)),
                1,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FrontierInput(RUN, 0, List.of(a, a, a, a, a), 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new FrontierInput(RUN, 0, List.of(a), 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> FrontierRequest.decode("{\"maxSteps\":1.0,\"readLineBudget\":10}"));
  }

  @Test
  void closedHttpRequestKeepsPreviewInvalidInputAndTodoDistinct() throws Exception {
    var checker = mock(FrontierChecker.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new FrontierController(checker))
            .setControllerAdvice(new FrontierExceptionHandler())
            .build();
    var route = "/api/plans/runs/" + RUN + "/frontier/check";
    var request = new FrontierRequest(2, 30);
    when(checker.check(RUN, request))
        .thenReturn(new FrontierReport(RUN, 9, List.of(), List.of(), 0, 30));
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content("{\"maxSteps\":2,\"readLineBudget\":30}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remainingLines").value(30));
    for (var body :
        List.of(
            "{\"maxSteps\":2,\"readLineBudget\":30,\"evidenceCurrent\":true}",
            "{\"maxSteps\":2,\"readLineBudget\":30,\"steps\":[]}",
            "{\"maxSteps\":2,\"readLineBudget\":30.0}",
            "{} {}"))
      mvc.perform(post(route).contentType("application/json").content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("FRONTIER_INVALID_REQUEST"));
    when(checker.check(RUN, request)).thenThrow(new UnsupportedOperationException("TODO"));
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content("{\"maxSteps\":2,\"readLineBudget\":30}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("FRONTIER_NOT_IMPLEMENTED"));
  }
}
