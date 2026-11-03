package dev.myfrei.repopilot.selection;

import java.util.*;

/** The request supplies version pins; the server supplies catalogue and byte costs. */
public record SelectionInput(List<Pin> requested, List<Candidate> catalog, int budgetBytes) {
  public SelectionInput {
    if (requested == null
        || requested.isEmpty()
        || requested.size() > 12
        || requested.stream().anyMatch(Objects::isNull)
        || catalog == null
        || catalog.size() > 4
        || catalog.stream().anyMatch(Objects::isNull)
        || budgetBytes < 1
        || budgetBytes > 4096) throw new IllegalArgumentException("Invalid selection bounds");
    requested = List.copyOf(requested);
    catalog = List.copyOf(catalog);
    if (catalog.stream().map(Candidate::name).distinct().count() != catalog.size())
      throw new IllegalArgumentException("Duplicate server catalogue name");
  }

  public record Pin(String name, String version) {
    public Pin {
      validateName(name);
      validateVersion(version);
    }
  }

  public record Candidate(String name, String version, int activationBytes) {
    public Candidate {
      validateName(name);
      validateVersion(version);
      if (activationBytes < 1 || activationBytes > 32768)
        throw new IllegalArgumentException("Invalid activation byte cost");
    }
  }

  static void validateName(String name) {
    if (name == null
        || !name.matches("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")
        || name.contains("--")) throw new IllegalArgumentException("Invalid skill name");
  }

  static void validateVersion(String version) {
    if (version == null || !version.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Expected lowercase SHA-256 version");
  }
}
