package dev.myfrei.repopilot.research;

/**
 * Admission limits apply cumulatively to parent and its single child; estimates are not actual
 * tokens.
 */
public record ResearchLimits(int modelCalls, int toolRequests, int estimatedTokenReservations) {
  public static final ResearchLimits DEFAULT = new ResearchLimits(8, 12, 48000);

  public ResearchLimits {
    if (modelCalls < 1
        || modelCalls > 8
        || toolRequests < 1
        || toolRequests > 12
        || estimatedTokenReservations < 1
        || estimatedTokenReservations > 131072)
      throw new IllegalArgumentException("Research limits exceed course maximum");
  }
}
