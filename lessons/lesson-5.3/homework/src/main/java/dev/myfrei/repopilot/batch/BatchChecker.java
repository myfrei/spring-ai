package dev.myfrei.repopilot.batch;

import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** All metadata and time originate on the server. No decisions and no writes. */
public class BatchChecker {
  private final Function<UUID, PatchProposal> readProposal;
  private final Predicate<UUID> isActive;
  private final Function<String, String> readHash;
  private final Clock clock;
  private final ProposalBatchPolicy policy;

  public BatchChecker(
      ControlledService proposals,
      WorkspaceService workspace,
      Clock clock,
      ProposalBatchPolicy policy) {
    this(proposals::proposal, proposals::isRunActive, workspace::currentHash, clock, policy);
  }

  /** Package-private test seam: no method capable of approving or writing is accepted. */
  BatchChecker(
      Function<UUID, PatchProposal> readProposal,
      Predicate<UUID> isActive,
      Function<String, String> readHash,
      Clock clock,
      ProposalBatchPolicy policy) {
    this.readProposal = Objects.requireNonNull(readProposal);
    this.isActive = Objects.requireNonNull(isActive);
    this.readHash = Objects.requireNonNull(readHash);
    this.clock = Objects.requireNonNull(clock);
    this.policy = Objects.requireNonNull(policy);
  }

  public BatchReport check(List<UUID> ids) {
    var request = new BatchRequest(ids);
    var now = clock.instant();
    var items = new ArrayList<BatchInput.Item>();
    var hashes = new HashMap<String, String>();
    var lookedUp = new HashSet<String>();
    for (var id : request.proposalIds()) {
      var proposal = readProposal.apply(id);
      String path = proposal.path();
      if (lookedUp.add(path))
        try {
          hashes.put(path, readHash.apply(path));
        } catch (RuntimeException unavailable) {
          /* unavailable is metadata, never success */
        }
      items.add(
          new BatchInput.Item(
              proposal.id(),
              path,
              proposal.mode(),
              proposal.status(),
              isActive.test(proposal.runId()),
              proposal.baseHash(),
              hashes.get(path),
              proposal.expiresAt()));
    }
    return policy.check(new BatchInput(now, items));
  }
}
