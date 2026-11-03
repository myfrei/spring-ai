package dev.myfrei.repopilot.journalrecall;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** The adapter supplies only paired server journal entries and current file hashes. */
public record RecallInput(
    UUID runId, List<String> requestedCallIds, List<Candidate> candidates, int payloadBudgetBytes) {
  public RecallInput {
    Objects.requireNonNull(runId);
    if (requestedCallIds == null
        || requestedCallIds.isEmpty()
        || requestedCallIds.size() > 8
        || candidates == null
        || candidates.size() > 8
        || candidates.stream().anyMatch(Objects::isNull)
        || payloadBudgetBytes < 1
        || payloadBudgetBytes > 4096) throw new IllegalArgumentException("Invalid recall bounds");
    requestedCallIds.forEach(RecallInput::validateCallId);
    requestedCallIds = List.copyOf(requestedCallIds);
    candidates = List.copyOf(candidates);
    if (candidates.stream().map(Candidate::callId).distinct().count() != candidates.size())
      throw new IllegalArgumentException("Ambiguous journal identifiers");
  }

  public record Candidate(
      UUID runId,
      String callId,
      String toolName,
      boolean toolSucceeded,
      String toolCode,
      String resultJson,
      String path,
      String fileHash,
      String currentHash) {
    public Candidate {
      Objects.requireNonNull(runId);
      validateCallId(callId);
      Objects.requireNonNull(toolName);
      if (toolCode == null
          || !toolCode.matches("[A-Z][A-Z0-9_]{0,63}")
          || resultJson == null
          || AgentJson.bytes(resultJson) > 4096)
        throw new IllegalArgumentException("Invalid result metadata");
      if (toolSucceeded && (path == null || fileHash == null || !fileHash.matches("[a-f0-9]{64}")))
        throw new IllegalArgumentException("Successful file metadata required");
    }
  }

  static void validateCallId(String id) {
    if (id == null || id.isBlank() || id.length() > 128)
      throw new IllegalArgumentException("Invalid tool call identifier");
  }
}
