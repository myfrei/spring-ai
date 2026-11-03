package dev.myfrei.repopilot.stability;

import dev.myfrei.repopilot.agent.AgentStore;
import dev.myfrei.repopilot.verification.*;
import java.util.*;
import java.util.function.*;

/** The adapter deliberately drops stdout, stderr and all free-form result text. */
public class VerificationStabilityChecker {
  private final Consumer<UUID> requireConversation;
  private final Function<UUID, VerificationReport> reports;
  private final VerificationStabilityPolicy policy;

  public VerificationStabilityChecker(
      AgentStore store, VerificationService service, VerificationStabilityPolicy policy) {
    this(id -> store.conversation(id), service::report, policy);
  }

  VerificationStabilityChecker(
      Consumer<UUID> requireConversation,
      Function<UUID, VerificationReport> reports,
      VerificationStabilityPolicy policy) {
    this.requireConversation = Objects.requireNonNull(requireConversation);
    this.reports = Objects.requireNonNull(reports);
    this.policy = Objects.requireNonNull(policy);
  }

  public VerificationStabilityReport check(UUID conversationId, StabilityRequest request) {
    requireConversation.accept(conversationId);
    var samples =
        request.reportIds().stream()
            .map(reports)
            .map(
                r ->
                    new VerificationStabilityInput.Sample(
                        r.id(),
                        r.conversationId(),
                        r.command(),
                        r.inputRevision(),
                        r.imageId(),
                        r.status(),
                        r.exitCode(),
                        r.inputsStillCurrent(),
                        r.outputTruncated()))
            .toList();
    return policy.check(new VerificationStabilityInput(conversationId, samples));
  }
}
