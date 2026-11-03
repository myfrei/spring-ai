package dev.myfrei.repopilot.agent;
public record AgentRunReport(int modelCalls,int toolCalls,int executedTools,int successfulTools,Integer providerInputTokens,Integer providerOutputTokens,boolean usageComplete,int estimatedInputTokens,int inputBudget,int includedHistoryTurns,int omittedHistoryTurns,String modelDigest,double elapsedMs){}
