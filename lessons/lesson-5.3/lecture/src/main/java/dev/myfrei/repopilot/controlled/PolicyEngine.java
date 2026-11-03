package dev.myfrei.repopilot.controlled;

import java.util.Objects;

/** Decisions belong to Java, never to text returned by a model or a file. */
public final class PolicyEngine {
  public enum Decision {
    DENY,
    PREVIEW,
    ASK
  }

  public Decision decide(RunMode mode) {
    return switch (Objects.requireNonNull(mode)) {
      case ASK -> Decision.DENY;
      case PLAN -> Decision.PREVIEW;
      case EDIT -> Decision.ASK;
    };
  }
}
