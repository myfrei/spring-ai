package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.AgentJson;

/** Provenance-checked candidate statement, never a semantic correctness or write authorization. */
public record ResearchFinding(
    String statement, String path, String fullFileHash, int startLine, int endLine) {
  public ResearchFinding {
    if (statement == null
        || statement.isBlank()
        || AgentJson.bytes(statement) > 240
        || statement.codePoints().anyMatch(Character::isISOControl)
        || path == null
        || AgentJson.bytes(path) > 240
        || !path.matches("(?:README\\.md|pom\\.xml|src/[A-Za-z0-9_./-]+)")
        || fullFileHash == null
        || !fullFileHash.matches("[0-9a-f]{64}")
        || startLine < 1
        || endLine < startLine
        || endLine > 100000
        || (long) endLine - startLine + 1 > 80)
      throw new IllegalArgumentException("Bounded finding required");
    for (String component : path.split("/"))
      if (component.isEmpty() || component.equals(".") || component.equals(".."))
        throw new IllegalArgumentException("Normalized finding path");
  }
}
