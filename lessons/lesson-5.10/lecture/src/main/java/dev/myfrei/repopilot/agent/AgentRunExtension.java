package dev.myfrei.repopilot.agent;

import java.util.*;
import java.util.function.Function;

/** Trusted per-run composition; tools are added explicitly and every definition is budgeted. */
public record AgentRunExtension(
    List<AgentTool> tools,
    Function<UUID, String> context,
    Function<UUID, String> terminal,
    AgentRunControl control) {
  public static final AgentRunExtension NONE =
      new AgentRunExtension(List.of(), ignored -> "", ignored -> null, AgentRunControl.NONE);

  public AgentRunExtension {
    tools = List.copyOf(tools);
    Objects.requireNonNull(context);
    Objects.requireNonNull(terminal);
    Objects.requireNonNull(control);
  }
}
