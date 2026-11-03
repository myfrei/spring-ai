package dev.myfrei.repopilot.agent;

public record ToolObservation(String name, String argumentsHash, String resultHash, boolean ok) {
  public ToolObservation {
    if (name == null
        || name.isBlank()
        || argumentsHash == null
        || !argumentsHash.matches("[0-9a-f]{64}")
        || resultHash == null
        || !resultHash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Bounded tool receipt required");
  }
}
