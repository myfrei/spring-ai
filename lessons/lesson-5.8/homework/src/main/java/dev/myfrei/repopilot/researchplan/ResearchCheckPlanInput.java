package dev.myfrei.repopilot.researchplan;

import dev.myfrei.repopilot.agent.AgentJson;
import java.util.*;

/** Accepted findings and delivered parent read spans, all collected on the server. */
public record ResearchCheckPlanInput(
    UUID parentRunId,
    List<Finding> findings,
    List<ParentRead> parentReads,
    Map<String, String> currentHashes,
    int readLineBudget) {
  public ResearchCheckPlanInput {
    Objects.requireNonNull(parentRunId);
    if (findings == null
        || findings.size() > 3
        || findings.stream().anyMatch(Objects::isNull)
        || parentReads == null
        || parentReads.size() > 8
        || parentReads.stream().anyMatch(Objects::isNull)
        || currentHashes == null
        || currentHashes.size() > 3
        || readLineBudget < 1
        || readLineBudget > 160) throw new IllegalArgumentException("Invalid read-plan bounds");
    findings = List.copyOf(findings);
    parentReads = List.copyOf(parentReads);
    currentHashes.forEach(
        (path, hash) -> {
          validatePath(path);
          validateHash(hash);
        });
    currentHashes = Map.copyOf(currentHashes);
  }

  public record Finding(String path, String fullFileHash, int startLine, int endLine) {
    public Finding {
      validatePath(path);
      validateHash(fullFileHash);
      validateRange(startLine, endLine);
    }
  }

  public record ParentRead(
      UUID parentRunId, String path, String fullFileHash, int startLine, int endLine) {
    public ParentRead {
      Objects.requireNonNull(parentRunId);
      validatePath(path);
      validateHash(fullFileHash);
      validateRange(startLine, endLine);
    }
  }

  static void validatePath(String path) {
    if (path == null
        || AgentJson.bytes(path) > 240
        || path.isBlank()
        || path.startsWith("/")
        || path.contains("\\")
        || path.codePoints().anyMatch(Character::isISOControl)
        || !(path.equals("README.md") || path.equals("pom.xml") || path.startsWith("src/")))
      throw new IllegalArgumentException("Invalid workspace path");
    for (var segment : path.split("/", -1))
      if (segment.isEmpty() || segment.startsWith("."))
        throw new IllegalArgumentException("Invalid path segment");
  }

  static void validateHash(String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Expected lowercase SHA-256");
  }

  static void validateRange(int start, int end) {
    if (start < 1 || end < start || end > 100000 || (long) end - start + 1 > 80)
      throw new IllegalArgumentException("Expected 1–80 complete lines");
  }
}
