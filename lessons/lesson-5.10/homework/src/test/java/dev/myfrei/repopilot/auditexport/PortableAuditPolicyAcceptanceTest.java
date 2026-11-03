package dev.myfrei.repopilot.auditexport;

import static org.junit.jupiter.api.Assertions.*;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.ingestion.Hashes;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortableAuditPolicyAcceptanceTest {
  static final String H = "a".repeat(64),
      OTHER = "b".repeat(64),
      IMAGE = "sha256:" + "c".repeat(64);
  static final UUID SESSION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
      PATCH = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  final PortableAuditPolicy policy = new PortableAuditPolicy();

  static PortableAuditInput base(int budget, PortableAuditInput.Details details) {
    return new PortableAuditInput(
        "STOCKROOM-001",
        H,
        "INCOMPLETE",
        IMAGE,
        new PortableAuditInput.Provenance(
            "stockroom-capstone-tasks", "getStockRoomTask", "2025-11-25", 1, 1, 1, H),
        List.of(
            new PortableAuditInput.Gate("MCP_IMPORTED", true),
            new PortableAuditInput.Gate("FRESH_PASS", false)),
        null,
        null,
        null,
        null,
        null,
        null,
        details,
        budget);
  }

  static PortableAuditInput.Details details() {
    return new PortableAuditInput.Details(
        "src/main/java/dev/myfrei/fixture/StockLedger.java",
        Map.of("session", SESSION, "patch", PATCH));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> payload(PortableAuditPacket packet) {
    var envelope = (Map<String, Object>) AgentJson.parse(packet.json());
    assertEquals(Set.of("payload", "sha256"), envelope.keySet());
    var payload = (Map<String, Object>) envelope.get("payload");
    String expected = Hashes.sha256(AgentJson.canonical(payload));
    assertEquals(expected, envelope.get("sha256"));
    assertEquals(expected, packet.sha256());
    assertEquals(AgentJson.bytes(packet.json()), packet.utf8Bytes());
    assertEquals(AgentJson.canonical(envelope), packet.json());
    return payload;
  }

  @Test
  void partialFactsRemainExplicitAndThePacketClaimsOnlyChecksumIntegrity() {
    var out = policy.export(base(16384, details()));
    var p = payload(out);
    assertEquals(
        Set.of(
            "schemaVersion",
            "format",
            "integrity",
            "task",
            "state",
            "imageId",
            "provenance",
            "gates",
            "revisions",
            "evidence",
            "details",
            "omittedFields"),
        p.keySet());
    assertEquals(1, p.get("schemaVersion"));
    assertEquals("REPOPILOT_AUDIT", p.get("format"));
    assertEquals("CHECKSUM_ONLY", p.get("integrity"));
    assertEquals("INCOMPLETE", p.get("state"));
    var evidence = (Map<?, ?>) p.get("evidence");
    assertTrue(evidence.containsKey("baseline"));
    assertNull(evidence.get("baseline"));
    assertNull(evidence.get("finalVerification"));
    assertNull(evidence.get("patch"));
    assertEquals(Boolean.FALSE, ((Map<?, ?>) p.get("gates")).get("FRESH_PASS"));
    assertTrue(((List<?>) p.get("omittedFields")).isEmpty());
  }

  @Test
  void semanticallyUnorderedGateAndIdMapsProduceTheSameCanonicalBytes() {
    var original = base(16384, details());
    var ids = new LinkedHashMap<String, UUID>();
    ids.put("patch", PATCH);
    ids.put("session", SESSION);
    var reversed =
        new PortableAuditInput(
            original.taskId(),
            original.taskDigest(),
            original.state(),
            original.imageId(),
            original.provenance(),
            List.of(original.gates().get(1), original.gates().get(0)),
            null,
            null,
            null,
            null,
            null,
            null,
            new PortableAuditInput.Details(details().relativeSourcePath(), ids),
            16384);
    assertEquals(policy.export(original), policy.export(reversed));
  }

  @Test
  void unsafeOptionalPathsAndUnknownIdKeysAreExplicitlyOmittedWithoutLeaks() {
    var input =
        base(
            16384,
            new PortableAuditInput.Details(
                "/Users/private/ABSOLUTE_SECRET.java", Map.of("UNKNOWN_SECRET_FIELD", SESSION)));
    var out = policy.export(input);
    var p = payload(out);
    assertEquals(Map.of(), p.get("details"));
    assertEquals(List.of("evidenceIds", "relativeSourcePath"), p.get("omittedFields"));
    for (var secret : List.of("ABSOLUTE_SECRET", "UNKNOWN_SECRET_FIELD", "/Users/private"))
      assertFalse(out.json().contains(secret));
  }

  @Test
  void optionalFieldsDropInContractOrderAndHashesAreRecomputedAfterEachDrop() {
    var noIds =
        policy.export(
            base(16384, new PortableAuditInput.Details(details().relativeSourcePath(), Map.of())));
    var fits = policy.export(base(noIds.utf8Bytes(), details()));
    assertEquals(noIds, fits);
    assertEquals(List.of("evidenceIds"), payload(fits).get("omittedFields"));
    var minimum = policy.export(base(16384, null));
    var withoutBoth = policy.export(base(minimum.utf8Bytes(), details()));
    assertEquals(minimum, withoutBoth);
    assertEquals(
        List.of("evidenceIds", "relativeSourcePath"), payload(withoutBoth).get("omittedFields"));
  }

  @Test
  void aUtf8BudgetNeverUsesStringLengthOrCutsAJsonPrefix() {
    var extra = new PortableAuditInput.Details("src/тест/😀.java", details().evidenceIds());
    var full = policy.export(base(16384, extra));
    assertTrue(full.utf8Bytes() > full.json().length());
    int budget = full.json().length();
    var limited = policy.export(base(budget, extra));
    assertTrue(limited.utf8Bytes() <= budget);
    var p = payload(limited);
    assertEquals(List.of("evidenceIds"), p.get("omittedFields"));
    assertEquals(
        extra.relativeSourcePath(), ((Map<?, ?>) p.get("details")).get("relativeSourcePath"));
  }

  @Test
  void requiredFactsAndTheFullRequiredDocumentCannotBeSilentlyDropped() {
    var source = base(16384, null);
    var minimum = policy.export(source);
    assertEquals(minimum, policy.export(base(minimum.utf8Bytes(), null)));
    var small =
        assertThrows(
            AuditExportProblem.class, () -> policy.export(base(minimum.utf8Bytes() - 1, null)));
    assertEquals("AUDIT_MAX_BYTES_TOO_SMALL", small.code());
    var missing =
        new PortableAuditInput(
            source.taskId(),
            null,
            source.state(),
            source.imageId(),
            source.provenance(),
            source.gates(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            16384);
    assertEquals(
        "AUDIT_REQUIRED_FACTS_MISSING",
        assertThrows(AuditExportProblem.class, () -> policy.export(missing)).code());
    var noProvenance =
        new PortableAuditInput(
            source.taskId(),
            source.taskDigest(),
            source.state(),
            source.imageId(),
            null,
            source.gates(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            16384);
    assertEquals(
        "AUDIT_REQUIRED_FACTS_MISSING",
        assertThrows(AuditExportProblem.class, () -> policy.export(noProvenance)).code());
  }

  @Test
  void typedEvidenceIsCopiedWithoutReclassifyingTheServerState() {
    var source = base(16384, null);
    var in =
        new PortableAuditInput(
            source.taskId(),
            H,
            "STALE",
            IMAGE,
            source.provenance(),
            source.gates(),
            H,
            OTHER,
            H,
            new PortableAuditInput.VerificationEvidence("FAIL", 1, H, IMAGE, true),
            new PortableAuditInput.VerificationEvidence("PASS", 0, OTHER, IMAGE, true),
            new PortableAuditInput.PatchEvidence("APPLIED", H, OTHER, "d".repeat(64)),
            null,
            16384);
    var p = payload(policy.export(in));
    assertEquals("STALE", p.get("state"));
    var evidence = (Map<?, ?>) p.get("evidence");
    assertEquals(1, ((Map<?, ?>) evidence.get("baseline")).get("exitCode"));
    assertEquals("PASS", ((Map<?, ?>) evidence.get("finalVerification")).get("status"));
    assertEquals(OTHER, ((Map<?, ?>) evidence.get("patch")).get("resultHash"));
    assertEquals(H, ((Map<?, ?>) p.get("revisions")).get("current"));
  }

  @Test
  void invalidRequiredIdentifiersHashesAndDuplicateGateCodesFailClosed() {
    var source = base(16384, null);
    var unsafe =
        new PortableAuditInput.Provenance(
            "https://host/TOKEN_SECRET", "getStockRoomTask", "2025-11-25", 1, 1, 1, H);
    var bad =
        new PortableAuditInput(
            source.taskId(),
            H,
            source.state(),
            IMAGE,
            unsafe,
            source.gates(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            16384);
    assertEquals(
        "AUDIT_INVALID_FACTS",
        assertThrows(AuditExportProblem.class, () -> policy.export(bad)).code());
    var duplicate =
        new PortableAuditInput(
            source.taskId(),
            H,
            source.state(),
            IMAGE,
            source.provenance(),
            List.of(
                new PortableAuditInput.Gate("MCP_IMPORTED", true),
                new PortableAuditInput.Gate("MCP_IMPORTED", false)),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            16384);
    assertEquals(
        "AUDIT_INVALID_FACTS",
        assertThrows(AuditExportProblem.class, () -> policy.export(duplicate)).code());
  }
}
