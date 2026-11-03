package dev.myfrei.repopilot.journalrecall;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.WorkspaceService;
import java.util.*;
import java.util.function.Function;

/** No question, assistant text or raw arguments leave this adapter. */
public class RecallChecker {
  private final Function<UUID, List<AgentMessage>> messages;
  private final Function<String, String> currentHash;
  private final ToolRecallPolicy policy;

  public RecallChecker(AgentStore store, WorkspaceService workspace, ToolRecallPolicy policy) {
    this(
        id -> {
          store.run(id);
          return store.messages(id);
        },
        workspace::currentHash,
        policy);
  }

  RecallChecker(
      Function<UUID, List<AgentMessage>> messages,
      Function<String, String> currentHash,
      ToolRecallPolicy policy) {
    this.messages = Objects.requireNonNull(messages);
    this.currentHash = Objects.requireNonNull(currentHash);
    this.policy = Objects.requireNonNull(policy);
  }

  public RecallReport check(UUID runId, RecallRequest request) {
    var calls = new HashMap<String, AgentMessage.ToolCall>();
    var replies = new HashMap<String, AgentMessage.ToolReply>();
    var ambiguous = new HashSet<String>();
    for (var message : messages.apply(runId)) {
      for (var call : message.toolCalls())
        if (calls.putIfAbsent(call.id(), call) != null) ambiguous.add(call.id());
      for (var reply : message.toolResults())
        if (replies.putIfAbsent(reply.id(), reply) != null) ambiguous.add(reply.id());
    }
    var candidates = new ArrayList<RecallInput.Candidate>();
    var hashes = new HashMap<String, String>();
    var lookedUp = new HashSet<String>();
    for (var id : new LinkedHashSet<>(request.callIds())) {
      var call = calls.get(id);
      var reply = replies.get(id);
      if (call == null
          || reply == null
          || ambiguous.contains(id)
          || !"function".equals(call.type())
          || !Objects.equals(call.name(), reply.name())) continue;
      String code = "INVALID_RESULT", raw = reply.resultJson(), path = null, fileHash = null;
      boolean ok = false;
      if (raw == null || AgentJson.bytes(raw) > 4096) raw = "";
      try {
        var envelope =
            AgentJson.arguments(raw, Set.of("ok", "code", "data"), Set.of("ok", "code", "data"));
        if (!(envelope.get("ok") instanceof Boolean successful)
            || !(envelope.get("code") instanceof String c)
            || !c.matches("[A-Z][A-Z0-9_]{0,63}"))
          throw new IllegalArgumentException("Invalid envelope");
        code = c;
        if (successful && "OK".equals(c) && "readFile".equals(call.name())) {
          if (!(envelope.get("data") instanceof Map<?, ?> data)
              || !validFileData(data)
              || !(data.get("path") instanceof String p)
              || !(data.get("fullFileHash") instanceof String h)
              || !h.matches("[a-f0-9]{64}")
              || !(data.get("truncated") instanceof Boolean)
              || !(data.get("rangeComplete") instanceof Boolean))
            throw new IllegalArgumentException("Invalid file result");
          path = p;
          fileHash = h;
          ok = true;
        }
      } catch (RuntimeException invalid) {
        code = "INVALID_RESULT";
        ok = false;
        path = null;
        fileHash = null;
      }
      String current = null;
      if (ok) {
        if (lookedUp.add(path))
          try {
            hashes.put(path, currentHash.apply(path));
          } catch (RuntimeException unavailable) {
            /* no current version means no recall */
          }
        current = hashes.get(path);
      }
      candidates.add(
          new RecallInput.Candidate(
              runId, id, call.name(), ok, code, raw, path, fileHash, current));
    }
    return policy.check(
        new RecallInput(runId, request.callIds(), candidates, request.payloadBudgetBytes()));
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
