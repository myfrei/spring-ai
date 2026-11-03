package dev.myfrei.repopilot.context;

public record ContextBudget(int windowTokens, int outputReserve, int safetyMargin) {
    public ContextBudget {
        if (windowTokens < 1 || outputReserve < 1 || safetyMargin < 0
                || (long) outputReserve + safetyMargin >= windowTokens) {
            throw new IllegalArgumentException("Invalid context budget");
        }
    }
    public int inputBudget() { return this.windowTokens - this.outputReserve - this.safetyMargin; }
}
