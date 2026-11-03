package dev.myfrei.repopilot.research;

public record ResearchBudgetSnapshot(
    ResearchLimits limits,
    int admittedModelCalls,
    int admittedToolRequests,
    int reservedEstimatedTokens,
    int observedModelCalls,
    Integer providerInputTokens,
    Integer providerOutputTokens,
    boolean usageComplete,
    int childrenStarted,
    int childModelCalls) {}
