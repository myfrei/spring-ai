package dev.myfrei.repopilot.research;

import java.util.*;

public record ResearchChildReport(
    UUID childRunId, String status, List<ResearchFinding> findings, List<String> uncertainties) {
  public ResearchChildReport {
    findings = List.copyOf(findings);
    uncertainties = List.copyOf(uncertainties);
  }
}
