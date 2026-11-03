package dev.myfrei.repopilot.capstone;

import java.util.*;

/** Every named evidence gate is mandatory. Missing checks cannot turn a partial run into DONE. */
public final class CapstoneCompletion {
  public static final List<String> REQUIRED =
      List.of(
          "MCP_IMPORTED",
          "BASELINE_FAIL",
          "BASELINE_OBSERVED",
          "PREPARE_COMPLETED",
          "PREPARE_PLAN",
          "SKILL_ACTIVATED",
          "PREPARE_READ",
          "PATCH_APPLIED",
          "PATCH_VERSION_CHAIN",
          "FRESH_PASS",
          "CURRENT_VERSION",
          "REVIEW_COMPLETED",
          "REVIEW_PLAN",
          "REVIEW_READ",
          "PASS_OBSERVED");

  public String state(List<CapstoneGate> gates) {
    if (gates == null
        || gates.size() != REQUIRED.size()
        || gates.stream().map(CapstoneGate::code).distinct().count() != REQUIRED.size()
        || !new HashSet<>(REQUIRED)
            .equals(new HashSet<>(gates.stream().map(CapstoneGate::code).toList())))
      throw new IllegalArgumentException("Complete known gate set required");
    if (gates.stream().allMatch(CapstoneGate::passed)) return "DONE";
    var byCode = new HashMap<String, Boolean>();
    gates.forEach(g -> byCode.put(g.code(), g.passed()));
    return byCode.get("PATCH_APPLIED") && !byCode.get("CURRENT_VERSION") ? "STALE" : "INCOMPLETE";
  }
}
