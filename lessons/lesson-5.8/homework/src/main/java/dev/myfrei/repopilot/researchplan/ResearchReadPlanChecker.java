package dev.myfrei.repopilot.researchplan;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.util.*;
import java.util.function.Function;

/** No statements, uncertainty text, questions, content or tool arguments enter the policy. */
public class ResearchReadPlanChecker {
  private final Function<UUID, ResearchSnapshot> snapshot;
  private final Function<UUID, List<AgentMessage>> messages;
  private final Function<String, String> currentHash;
  private final ResearchCheckPlanPolicy policy;

  public ResearchReadPlanChecker(
      ResearchRuntime runtime,
      AgentStore store,
      WorkspaceService workspace,
      ResearchCheckPlanPolicy policy) {
    this(
        runtime::snapshot,
        id -> {
          store.run(id);
          return store.messages(id);
        },
        workspace::currentHash,
        policy);
  }

  ResearchReadPlanChecker(
      Function<UUID, ResearchSnapshot> snapshot,
      Function<UUID, List<AgentMessage>> messages,
      Function<String, String> currentHash,
      ResearchCheckPlanPolicy policy) {
    this.snapshot = Objects.requireNonNull(snapshot);
    this.messages = Objects.requireNonNull(messages);
    this.currentHash = Objects.requireNonNull(currentHash);
    this.policy = Objects.requireNonNull(policy);
  }

  public ResearchCheckPlanReport check(UUID parentRunId, ResearchReadPlanRequest request) {
    var report = snapshot.apply(parentRunId);
    if (!parentRunId.equals(report.parentRunId()))
      throw new ResearchReadPlanProblem("READ_PLAN_SCOPE_MISMATCH");
    var findings =
        report.children().stream()
            .filter(child -> child.status().equals("CANDIDATE"))
            .flatMap(child -> child.findings().stream())
            .map(
                f ->
                    new ResearchCheckPlanInput.Finding(
                        f.path(), f.fullFileHash(), f.startLine(), f.endLine()))
            .toList();
    if (findings.size() > 3) throw new ResearchReadPlanProblem("READ_PLAN_EVIDENCE_LIMIT");
    var hashes = new HashMap<String, String>();
    var paths = new LinkedHashSet<String>();
    findings.forEach(f -> paths.add(f.path()));
    for (var path : paths)
      try {
        var hash = currentHash.apply(path);
        if (hash != null) hashes.put(path, hash);
      } catch (RuntimeException unavailable) {
        /* an absent current version is not evidence */
      }
    return policy.check(
        new ResearchCheckPlanInput(
            parentRunId,
            findings,
            parentReads(parentRunId, messages.apply(parentRunId)),
            hashes,
            request.readLineBudget()));
  }

  private static List<ResearchCheckPlanInput.ParentRead> parentReads(
      UUID parentRunId, List<AgentMessage> messages) {
    var calls = new HashMap<String, AgentMessage.ToolCall>();
    var replies = new HashMap<String, AgentMessage.ToolReply>();
    var ambiguous = new HashSet<String>();
    for (var message : messages) {
      for (var call : message.toolCalls())
        if (calls.putIfAbsent(call.id(), call) != null) ambiguous.add(call.id());
      for (var reply : message.toolResults())
        if (replies.putIfAbsent(reply.id(), reply) != null) ambiguous.add(reply.id());
    }
    var reads = new ArrayList<ResearchCheckPlanInput.ParentRead>();
    for (var entry : replies.entrySet()) {
      var call = calls.get(entry.getKey());
      var reply = entry.getValue();
      if (call == null
          || ambiguous.contains(entry.getKey())
          || !"function".equals(call.type())
          || !"readFile".equals(call.name())
          || !call.name().equals(reply.name())) continue;
      try {
        String raw = reply.resultJson();
        if (raw == null || AgentJson.bytes(raw) > 4096) continue;
        var envelope =
            AgentJson.arguments(raw, Set.of("ok", "code", "data"), Set.of("ok", "code", "data"));
        if (!Boolean.TRUE.equals(envelope.get("ok"))
            || !"OK".equals(envelope.get("code"))
            || !(envelope.get("data") instanceof Map<?, ?> data)
            || !validFileData(data)
            || !(data.get("path") instanceof String path)
            || !(data.get("fullFileHash") instanceof String hash)
            || !(data.get("startLine") instanceof Integer start)
            || !(data.get("endLine") instanceof Integer end)) continue;
        reads.add(new ResearchCheckPlanInput.ParentRead(parentRunId, path, hash, start, end));
      } catch (RuntimeException malformed) {
        /* malformed journal entries never prove a read */
      }
    }
    if (reads.size() > 8) throw new ResearchReadPlanProblem("READ_PLAN_EVIDENCE_LIMIT");
    return List.copyOf(reads);
  }

  private static boolean validFileData(Map<?, ?> data) {
    if (!data.keySet()
            .equals(
                Set.of(
                    "path",
                    "fullFileHash",
                    "totalBytes",
                    "totalLines",
                    "startLine",
                    "endLine",
                    "content",
                    "truncated",
                    "rangeComplete"))
        || !(data.get("content") instanceof String)
        || !(data.get("truncated") instanceof Boolean)
        || !(data.get("rangeComplete") instanceof Boolean)
        || !(data.get("totalBytes") instanceof Number bytes)
        || !(bytes instanceof Integer || bytes instanceof Long)
        || !(data.get("totalLines") instanceof Integer lines)
        || !(data.get("startLine") instanceof Integer start)
        || !(data.get("endLine") instanceof Integer end)) return false;
    return bytes.longValue() >= 0
        && bytes.longValue() <= 65536
        && lines >= 1
        && lines <= 65536
        && start >= 1
        && end >= start
        && end <= lines
        && end - start < 80;
  }
}
