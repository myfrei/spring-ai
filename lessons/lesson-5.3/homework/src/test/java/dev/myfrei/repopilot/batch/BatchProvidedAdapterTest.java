package dev.myfrei.repopilot.batch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.workspace.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BatchProvidedAdapterTest {
  static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
  static final String H = "a".repeat(64);
  static final UUID RUN = UUID.randomUUID(), CONVERSATION = UUID.randomUUID();

  static PatchProposal proposal(UUID id, String path) {
    return new PatchProposal(
        id,
        RUN,
        CONVERSATION,
        "EDIT",
        path,
        H,
        H,
        H,
        "PENDING",
        "preview",
        NOW.minusSeconds(5),
        NOW.plusSeconds(60));
  }

  @Test
  void closedDecoderAcceptsIdsOnlyAndRejectsForgedMetadata() {
    var id = UUID.randomUUID();
    assertEquals(
        List.of(id), BatchRequest.decode("{\"proposalIds\":[\"" + id + "\"]}").proposalIds());
    for (String bad :
        List.of(
            "{}",
            "{\"proposalIds\":[]}",
            "{\"proposalIds\":[null]}",
            "{\"proposalIds\":[\"" + id + "\"],\"currentHashes\":{}}",
            "{\"proposalIds\":[\"" + id + "\"],\"status\":\"PENDING\"}",
            "{\"proposalIds\":[\"" + id + "\"],\"proposalIds\":[\"" + id + "\"]}"))
      assertThrows(IllegalArgumentException.class, () -> BatchRequest.decode(bad));
  }

  @Test
  void adapterCollectsServerMetadataOncePerPathWithoutDecidingOrWriting() {
    var policy = mock(ProposalBatchPolicy.class);
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    var stored = Map.of(a, proposal(a, "src/A.java"), b, proposal(b, "src/B.java"));
    var reads = new HashMap<String, Integer>();
    java.util.function.Function<String, String> readHash =
        path -> {
          reads.merge(path, 1, Integer::sum);
          if (path.equals("src/B.java")) throw new IllegalArgumentException("unavailable");
          return H;
        };
    var expected = new BatchReport(List.of(), false);
    when(policy.check(any())).thenReturn(expected);
    assertSame(
        expected,
        new BatchChecker(
                stored::get, run -> true, readHash, Clock.fixed(NOW, ZoneOffset.UTC), policy)
            .check(List.of(a, a, b)));
    var arg = ArgumentCaptor.forClass(BatchInput.class);
    verify(policy).check(arg.capture());
    assertEquals(NOW, arg.getValue().now());
    assertEquals(3, arg.getValue().items().size());
    assertTrue(arg.getValue().items().stream().allMatch(BatchInput.Item::runActive));
    assertEquals(H, arg.getValue().items().getFirst().currentHash());
    assertNull(arg.getValue().items().getLast().currentHash());
    assertEquals(1, reads.get("src/A.java"));
    assertEquals(1, reads.get("src/B.java"));
  }

  @Test
  void httpKeepsTodoDistinctFromMalformedInputAndSuccessfulPreview() throws Exception {
    var checker = mock(BatchChecker.class);
    var id = UUID.randomUUID();
    var mvc =
        MockMvcBuilders.standaloneSetup(new BatchController(checker))
            .setControllerAdvice(new BatchExceptionHandler())
            .build();
    var body = "{\"proposalIds\":[\"" + id + "\"]}";
    when(checker.check(List.of(id)))
        .thenReturn(
            new BatchReport(List.of(new BatchReport.Check(id, "src/A.java", "READY")), true));
    mvc.perform(
            post("/api/controlled/proposals/batch-check")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(true));
    mvc.perform(
            post("/api/controlled/proposals/batch-check")
                .contentType("application/json")
                .content("{\"proposalIds\":[\"" + id + "\"],\"approved\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BATCH_INVALID_REQUEST"));
    when(checker.check(List.of(id))).thenThrow(new UnsupportedOperationException("TODO"));
    mvc.perform(
            post("/api/controlled/proposals/batch-check")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("BATCH_NOT_IMPLEMENTED"));
  }
}
