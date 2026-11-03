package dev.myfrei.repopilot.attention;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.ui.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AttentionProvidedAdapterTest {
  static final UUID CONVERSATION = UUID.randomUUID();

  static SessionSnapshot snapshot(UUID id) {
    return new SessionSnapshot(
        id,
        CONVERSATION,
        UUID.randomUUID(),
        "STOPPED",
        UUID.randomUUID(),
        "CONTEXT_LIMIT",
        5,
        1,
        null,
        List.of(new ApprovalRef("PATCH", UUID.randomUUID(), "PENDING", "SUMMARY_SECRET")),
        null,
        null,
        null);
  }

  @Test
  void closedDecoderRejectsForgedStatesInvalidAcksAndUnknownFields() {
    var id = UUID.randomUUID();
    assertEquals(
        -1L,
        new AttentionQueueInput.Session(id, "RUNNING", "", 1, -1, List.of()).acknowledgedThrough());
    assertEquals(
        Map.of(id, 5L),
        AttentionRequest.decode("{\"maxItems\":12,\"acknowledgedThrough\":{\"" + id + "\":5}}")
            .acknowledgedThrough());
    for (var bad :
        List.of(
            "{\"maxItems\":0}",
            "{\"maxItems\":13}",
            "{\"maxItems\":1.0}",
            "{\"maxItems\":1,\"sessions\":[]}",
            "{\"maxItems\":1,\"acknowledgedThrough\":{\"" + id + "\":-1}}",
            "{\"maxItems\":1,\"acknowledgedThrough\":{\"" + id + "\":1.5}}"))
      assertThrows(IllegalArgumentException.class, () -> AttentionRequest.decode(bad));
  }

  @Test
  void adapterUsesScopedServerSnapshotsDropsSummaryAndRejectsUnknownOrFutureAcks() {
    var id = UUID.randomUUID();
    var snapshot = snapshot(id);
    var policy = mock(AttentionQueuePolicy.class);
    when(policy.check(any()))
        .thenReturn(new AttentionQueueReport(CONVERSATION, List.of(), 0, 0, 0, 0, true, Map.of()));
    var checker =
        new AttentionQueueChecker(
            c -> assertEquals(CONVERSATION, c), c -> List.of(snapshot), policy);
    checker.check(CONVERSATION, new AttentionRequest(12, Map.of(id, 3L)));
    var input = ArgumentCaptor.forClass(AttentionQueueInput.class);
    verify(policy).check(input.capture());
    assertEquals(5, input.getValue().sessions().getFirst().lastSequence());
    assertEquals(3, input.getValue().sessions().getFirst().acknowledgedThrough());
    assertFalse(AgentJson.encode(input.getValue()).contains("SUMMARY_SECRET"));
    assertThrows(
        IllegalArgumentException.class,
        () -> checker.check(CONVERSATION, new AttentionRequest(12, Map.of(id, 6L))));
    assertThrows(
        IllegalArgumentException.class,
        () -> checker.check(CONVERSATION, new AttentionRequest(12, Map.of(UUID.randomUUID(), 0L))));
  }

  @Test
  void httpSeparatesReadonlyQueueForgedMetadataAndTodo() throws Exception {
    var checker = mock(AttentionQueueChecker.class);
    var request = new AttentionRequest(12, Map.of());
    when(checker.check(CONVERSATION, request))
        .thenReturn(new AttentionQueueReport(CONVERSATION, List.of(), 0, 0, 0, 0, true, Map.of()));
    var mvc =
        MockMvcBuilders.standaloneSetup(new AttentionController(checker))
            .setControllerAdvice(new AttentionExceptionHandler())
            .build();
    var route = "/api/sessions/conversations/" + CONVERSATION + "/attention/check";
    mvc.perform(post(route).contentType("application/json").content("{\"maxItems\":12}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allActionableVisible").value(true));
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content("{\"maxItems\":12,\"status\":\"COMPLETED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ATTENTION_INVALID_REQUEST"));
    doThrow(new UnsupportedOperationException("TODO")).when(checker).check(CONVERSATION, request);
    mvc.perform(post(route).contentType("application/json").content("{\"maxItems\":12}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("ATTENTION_NOT_IMPLEMENTED"));
  }
}
