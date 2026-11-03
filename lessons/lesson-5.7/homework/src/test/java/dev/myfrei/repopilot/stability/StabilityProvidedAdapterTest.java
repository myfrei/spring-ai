package dev.myfrei.repopilot.stability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.verification.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StabilityProvidedAdapterTest {
  static final UUID CONVERSATION = UUID.randomUUID();

  static VerificationReport report(UUID id) {
    return new VerificationReport(
        id,
        UUID.randomUUID(),
        CONVERSATION,
        "STOCKROOM_CONTRACT_V1",
        "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "PASS",
        0,
        "STDOUT_SECRET",
        "STDERR_SECRET",
        true,
        true,
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(1),
        1000,
        "REASON_SECRET");
  }

  @Test
  void closedDecoderAcceptsOnlyBoundedCanonicalReportIdentifiers() {
    var id = UUID.randomUUID();
    var json = "{\"reportIds\":[\"" + id + "\",\"" + id + "\"]}";
    assertEquals(List.of(id, id), StabilityRequest.decode(json).reportIds());
    for (var bad :
        List.of(
            "{}",
            "{\"reportIds\":[]}",
            "{\"reportIds\":[null]}",
            json.replace("}", ",\"exitCode\":0}"),
            json.replace("}", ",\"reports\":[]}"),
            json + " {}"))
      assertThrows(IllegalArgumentException.class, () -> StabilityRequest.decode(bad));
  }

  @Test
  void adapterUsesOnlyServerMetadataAndDropsAllLogAndReasonText() {
    var id = UUID.randomUUID();
    var stored = report(id);
    var lookups = new AtomicInteger();
    var scope = new AtomicInteger();
    var policy = mock(VerificationStabilityPolicy.class);
    when(policy.check(any()))
        .thenReturn(new VerificationStabilityReport(CONVERSATION, List.of(), List.of()));
    var checker =
        new VerificationStabilityChecker(
            c -> {
              assertEquals(CONVERSATION, c);
              scope.incrementAndGet();
            },
            requested -> {
              assertEquals(id, requested);
              lookups.incrementAndGet();
              return stored;
            },
            policy);
    checker.check(CONVERSATION, new StabilityRequest(List.of(id, id)));
    var input = ArgumentCaptor.forClass(VerificationStabilityInput.class);
    verify(policy).check(input.capture());
    assertEquals(2, input.getValue().samples().size());
    assertTrue(input.getValue().samples().getFirst().outputTruncated());
    assertEquals(0, input.getValue().samples().getFirst().exitCode());
    assertEquals(1, scope.get());
    assertEquals(2, lookups.get());
    String encoded = AgentJson.encode(input.getValue());
    for (var secret : List.of("STDOUT_SECRET", "STDERR_SECRET", "REASON_SECRET"))
      assertFalse(encoded.contains(secret));
  }

  @Test
  void httpDistinguishesPreviewForgedMetadataUnknownReportsAndTodo() throws Exception {
    var checker = mock(VerificationStabilityChecker.class);
    var id = UUID.randomUUID();
    var request = new StabilityRequest(List.of(id));
    var route = "/api/verification/conversations/" + CONVERSATION + "/stability/check";
    var json = "{\"reportIds\":[\"" + id + "\"]}";
    var mvc =
        MockMvcBuilders.standaloneSetup(new StabilityController(checker))
            .setControllerAdvice(new StabilityExceptionHandler())
            .build();
    when(checker.check(CONVERSATION, request))
        .thenReturn(new VerificationStabilityReport(CONVERSATION, List.of(), List.of()));
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isOk());
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content(json.replace("}", ",\"status\":\"PASS\"}")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("STABILITY_INVALID_REQUEST"));
    when(checker.check(CONVERSATION, request))
        .thenThrow(new VerificationProblem("VERIFICATION_REPORT_NOT_FOUND"));
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isNotFound());
    doThrow(new UnsupportedOperationException("TODO")).when(checker).check(CONVERSATION, request);
    mvc.perform(post(route).contentType("application/json").content(json))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("STABILITY_NOT_IMPLEMENTED"));
  }
}
