package dev.myfrei.repopilot.auditexport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.capstone.*;
import dev.myfrei.repopilot.controlled.PatchProposal;
import dev.myfrei.repopilot.verification.VerificationReport;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditExportProvidedAdapterTest {
  static final UUID SESSION = UUID.randomUUID(),
      CONVERSATION = UUID.randomUUID(),
      BASELINE = UUID.randomUUID(),
      PASS = UUID.randomUUID(),
      PATCH = UUID.randomUUID();
  static final String H = "a".repeat(64), IMAGE = "sha256:" + "b".repeat(64);

  static CapstoneReport report() {
    return new CapstoneReport(
        SESSION,
        "STOCKROOM-001",
        CONVERSATION,
        "DONE",
        H,
        new McpImportReceipt(
            "stockroom-capstone-tasks",
            "getStockRoomTask",
            "2025-11-25",
            1,
            1,
            1,
            H,
            Instant.EPOCH),
        List.of(new CapstoneGate("MCP_IMPORTED", true)),
        UUID.randomUUID(),
        UUID.randomUUID(),
        BASELINE,
        PASS,
        PATCH,
        H,
        H,
        H,
        IMAGE,
        "src/main/java/dev/myfrei/fixture/StockLedger.java",
        "CAPSTONE_DIFF_SECRET",
        Instant.EPOCH,
        null,
        null);
  }

  static VerificationReport verification(UUID id) {
    return new VerificationReport(
        id,
        UUID.randomUUID(),
        CONVERSATION,
        "STOCKROOM_CONTRACT_V1",
        H,
        IMAGE,
        id.equals(PASS) ? "PASS" : "FAIL",
        id.equals(PASS) ? 0 : 1,
        "STDOUT_SECRET",
        "STDERR_SECRET",
        false,
        true,
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(1),
        1000,
        "REASON_SECRET");
  }

  @Test
  void closedRequestDecoderAcceptsOnlyTheByteBudget() {
    assertEquals(16384, AuditExportRequest.decode("{\"maxBytes\":16384}").maxBytes());
    for (var bad :
        List.of(
            "{\"maxBytes\":0}",
            "{\"maxBytes\":16385}",
            "{\"maxBytes\":1.0}",
            "{\"maxBytes\":100,\"state\":\"DONE\"}",
            "{\"maxBytes\":100,\"report\":{}}",
            "{\"maxBytes\":100,\"diff\":\"fake\"}"))
      assertThrows(IllegalArgumentException.class, () -> AuditExportRequest.decode(bad));
  }

  @Test
  void adapterExtractsStructuredServerFactsAndNeverPassesRawProofsOrTimesToPolicy() {
    var policy = mock(PortableAuditPolicy.class);
    when(policy.export(any())).thenReturn(new PortableAuditPacket("{}", H, 2));
    var source = report();
    var calls = new ArrayList<UUID>();
    var checker =
        new AuditExportChecker(
            id -> {
              assertEquals(SESSION, id);
              return source;
            },
            id -> {
              calls.add(id);
              return verification(id);
            },
            id -> {
              assertEquals(PATCH, id);
              return new PatchProposal(
                  id,
                  UUID.randomUUID(),
                  CONVERSATION,
                  "EDIT",
                  "src/main/java/dev/myfrei/fixture/StockLedger.java",
                  H,
                  H,
                  H,
                  "APPLIED",
                  "PATCH_DIFF_SECRET",
                  Instant.EPOCH,
                  Instant.EPOCH.plusSeconds(300));
            },
            policy);
    checker.export(SESSION, new AuditExportRequest(10000));
    var input = ArgumentCaptor.forClass(PortableAuditInput.class);
    verify(policy).export(input.capture());
    assertEquals(List.of(BASELINE, PASS), calls);
    assertEquals("FAIL", input.getValue().baseline().status());
    assertEquals("PASS", input.getValue().finalVerification().status());
    assertEquals("APPLIED", input.getValue().patch().status());
    assertEquals(SESSION, input.getValue().details().evidenceIds().get("session"));
    String encoded = AgentJson.encode(input.getValue());
    for (var text :
        List.of(
            "CAPSTONE_DIFF_SECRET",
            "PATCH_DIFF_SECRET",
            "STDOUT_SECRET",
            "STDERR_SECRET",
            "REASON_SECRET",
            "generatedAt",
            "importedAt",
            "conversationId")) assertFalse(encoded.contains(text));
  }

  @Test
  void httpDownloadsExactJsonAndSeparatesBudgetFailureForgedMetadataAndTodo() throws Exception {
    var checker = mock(AuditExportChecker.class);
    var request = new AuditExportRequest(1000);
    String json = "{\"payload\":{},\"sha256\":\"" + H + "\"}";
    when(checker.export(SESSION, request))
        .thenReturn(new PortableAuditPacket(json, H, AgentJson.bytes(json)));
    var mvc =
        MockMvcBuilders.standaloneSetup(new AuditExportController(checker))
            .setControllerAdvice(new AuditExportExceptionHandler())
            .build();
    var route = "/api/capstone/sessions/" + SESSION + "/audit/export";
    mvc.perform(post(route).contentType("application/json").content("{\"maxBytes\":1000}"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", "attachment; filename=\"repopilot-audit.json\""))
        .andExpect(header().string("X-Audit-SHA256", H))
        .andExpect(content().string(json));
    mvc.perform(
            post(route)
                .contentType("application/json")
                .content("{\"maxBytes\":1000,\"state\":\"DONE\"}"))
        .andExpect(status().isBadRequest());
    doThrow(new AuditExportProblem("AUDIT_MAX_BYTES_TOO_SMALL"))
        .when(checker)
        .export(SESSION, request);
    mvc.perform(post(route).contentType("application/json").content("{\"maxBytes\":1000}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("AUDIT_MAX_BYTES_TOO_SMALL"));
    doThrow(new UnsupportedOperationException("TODO")).when(checker).export(SESSION, request);
    mvc.perform(post(route).contentType("application/json").content("{\"maxBytes\":1000}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("AUDIT_NOT_IMPLEMENTED"));
  }
}
