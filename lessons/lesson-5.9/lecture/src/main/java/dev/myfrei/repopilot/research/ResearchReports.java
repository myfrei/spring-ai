package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.workspace.*;
import java.util.*;

/**
 * Child JSON is untrusted; receipts prove delivered bytes/ranges, never the truth of a statement.
 */
public final class ResearchReports {
  private ResearchReports() {}

  public static ResearchChildReport parse(
      String answer, UUID childRunId, WorkspaceService workspace) {
    try {
      if (answer == null || AgentJson.bytes(answer) > 2048) throw new IllegalArgumentException();
      var root =
          AgentJson.arguments(
              answer, Set.of("findings", "uncertainties"), Set.of("findings", "uncertainties"));
      if (!(root.get("findings") instanceof List<?> list)
          || list.size() > 3
          || !(root.get("uncertainties") instanceof List<?> unknown)
          || unknown.size() > 3) throw new IllegalArgumentException();
      var findings = new ArrayList<ResearchFinding>();
      var uncertainties = new ArrayList<String>();
      for (Object value : list) {
        if (!(value instanceof Map<?, ?> map)
            || !map.keySet()
                .equals(Set.of("statement", "path", "fullFileHash", "startLine", "endLine"))
            || !(map.get("statement") instanceof String statement)
            || !(map.get("path") instanceof String path)
            || !(map.get("fullFileHash") instanceof String hash)
            || !(map.get("startLine") instanceof Integer start)
            || !(map.get("endLine") instanceof Integer end)) throw new IllegalArgumentException();
        findings.add(new ResearchFinding(statement, path, hash, start, end));
      }
      for (Object value : unknown) {
        if (!(value instanceof String text)
            || text.isBlank()
            || AgentJson.bytes(text) > 160
            || text.codePoints().anyMatch(Character::isISOControl))
          throw new IllegalArgumentException();
        uncertainties.add(text);
      }
      for (var finding : findings) {
        if (!workspace.currentHash(finding.path()).equals(finding.fullFileHash())
            || !covered(workspace.receipts(childRunId.toString()), childRunId, finding))
          throw new IllegalArgumentException();
      }
      return new ResearchChildReport(childRunId, "CANDIDATE", findings, uncertainties);
    } catch (RuntimeException invalid) {
      throw new ResearchException("RESEARCH_REPORT_INVALID");
    }
  }

  public static boolean covered(List<ReadReceipt> receipts, UUID runId, ResearchFinding finding) {
    long next = finding.startLine();
    var ranges =
        receipts.stream()
            .filter(
                r ->
                    r.runId().equals(runId.toString())
                        && r.path().equals(finding.path())
                        && r.fullFileHash().equals(finding.fullFileHash()))
            .sorted(Comparator.comparingInt(ReadReceipt::startLine))
            .toList();
    for (var range : ranges) {
      if (range.startLine() > next) break;
      if (range.endLine() >= next) next = (long) range.endLine() + 1;
    }
    return next > finding.endLine();
  }

  public static String parentGate(
      UUID parentRunId, List<ResearchChildReport> children, WorkspaceService workspace) {
    for (var child : children)
      for (var finding : child.findings()) {
        try {
          if (!workspace.currentHash(finding.path()).equals(finding.fullFileHash()))
            return "RESEARCH_EVIDENCE_STALE";
          if (!covered(workspace.receipts(parentRunId.toString()), parentRunId, finding))
            return "RESEARCH_NOT_RECHECKED";
        } catch (WorkspaceException unavailable) {
          return unavailable.code().equals("RECEIPTS_UNAVAILABLE")
              ? "RESEARCH_NOT_RECHECKED"
              : "RESEARCH_EVIDENCE_UNAVAILABLE";
        }
      }
    return null;
  }
}
