package dev.myfrei.repopilot.skills;

/** Only this bounded metadata, never instruction bodies, enters the initial catalogue. */
public record SkillMetadata(
    String name, String description, String version, int instructionBytes, int referenceBytes) {}
