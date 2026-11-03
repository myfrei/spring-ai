package dev.myfrei.repopilot.attention;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.ui.*;
import java.util.*;
import java.util.function.*;

/** Summary text, answers, plans and raw events never enter the display policy. */
public class AttentionQueueChecker {
  private final Consumer<UUID> requireConversation;
  private final Function<UUID, List<SessionSnapshot>> sessions;
  private final AttentionQueuePolicy policy;

  public AttentionQueueChecker(
      AgentStore store, SessionCoordinator coordinator, AttentionQueuePolicy policy) {
    this(id -> store.conversation(id), coordinator::sessions, policy);
  }

  AttentionQueueChecker(
      Consumer<UUID> requireConversation,
      Function<UUID, List<SessionSnapshot>> sessions,
      AttentionQueuePolicy policy) {
    this.requireConversation = Objects.requireNonNull(requireConversation);
    this.sessions = Objects.requireNonNull(sessions);
    this.policy = Objects.requireNonNull(policy);
  }

  public AttentionQueueReport check(UUID conversationId, AttentionRequest request) {
    requireConversation.accept(conversationId);
    var snapshots = sessions.apply(conversationId);
    var high = new HashMap<UUID, Long>();
    for (var s : snapshots) {
      if (!conversationId.equals(s.conversationId()))
        throw new AttentionProblem("ATTENTION_SCOPE_MISMATCH");
      high.merge(s.id(), s.lastSequence(), Math::max);
    }
    for (var ack : request.acknowledgedThrough().entrySet())
      if (!high.containsKey(ack.getKey()) || ack.getValue() > high.get(ack.getKey()))
        throw new IllegalArgumentException("Unknown or future acknowledgement");
    var items =
        snapshots.stream()
            .map(
                s ->
                    new AttentionQueueInput.Session(
                        s.id(),
                        s.status(),
                        s.stopReason(),
                        s.lastSequence(),
                        request.acknowledgedThrough().getOrDefault(s.id(), -1L),
                        s.approvals().stream()
                            .map(
                                a -> new AttentionQueueInput.Approval(a.kind(), a.id(), a.status()))
                            .toList()))
            .toList();
    return policy.check(new AttentionQueueInput(conversationId, items, request.maxItems()));
  }
}
