package dev.myfrei.repopilot.agent;

import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.springframework.ai.chat.messages.Message;

/**
 * Server-owned prompt projection. The full current journal remains in AgentStore. Null denies
 * admission. Returned messages are independently checked against the real tool budget.
 * System/current invariants are the responsibility of a trusted implementation, not model text.
 */
@FunctionalInterface
public interface PromptProjection {
  List<Message> project(
      UUID runId,
      List<Message> system,
      List<List<Message>> completedHistory,
      List<Message> current,
      int inputBudget,
      ToIntFunction<List<Message>> estimate);
}
