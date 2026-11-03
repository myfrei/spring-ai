package dev.myfrei.repopilot.workspace;

import dev.myfrei.repopilot.agent.*;
import java.util.*;
import java.util.function.BiFunction;

/** Thin adapters: raw strict JSON -> bounded domain data. No shell or instruction interpreter. */
public final class WorkspaceAgentTools {
  private WorkspaceAgentTools() {}

  public static List<AgentTool> create(WorkspaceService service) {
    var directory =
        Map.<String, Object>of(
            "type",
            "string",
            "description",
            "Relative directory, normally . or src; no absolute paths.");
    var limit =
        Map.<String, Object>of(
            "type", "integer", "minimum", 1, "maximum", WorkspaceService.MAX_ITEMS);
    var path =
        Map.<String, Object>of(
            "type",
            "string",
            "description",
            "Exact permitted relative file path from listFiles/searchText.");
    var line = Map.<String, Object>of("type", "integer", "minimum", 1, "maximum", 1000000);
    return List.of(
        tool(
            "listFiles",
            "List permitted file metadata under configured workspace; truncated/scanComplete"
                + " describe bounds.",
            Map.of("directory", directory, "maxEntries", limit),
            Set.of(),
            (a, c) -> service.listFiles(string(a, "directory", "."), integer(a, "maxEntries", 20))),
        tool(
            "searchText",
            "Literal case-sensitive text search in permitted UTF-8 files. Snippets are not read"
                + " receipts. No regex, no commands.",
            Map.of("query", Map.of("type", "string"), "directory", directory, "maxResults", limit),
            Set.of("query"),
            (a, c) ->
                service.searchText(
                    string(a, "query", null),
                    string(a, "directory", "."),
                    integer(a, "maxResults", 10))),
        tool(
            "readFile",
            "Read inclusive complete lines of a permitted UTF-8 file. Full byte SHA-256 plus actual"
                + " returned range; server records a same-run receipt. At most80 requested lines.",
            Map.of("path", path, "startLine", line, "endLine", line),
            Set.of("path", "startLine", "endLine"),
            (a, c) ->
                service.readFile(
                    c.runId().toString(),
                    string(a, "path", null),
                    integer(a, "startLine", 0),
                    integer(a, "endLine", 0))),
        tool(
            "gitStatus",
            "Read bounded filtered porcelain-v1 Git status including untracked files and renames."
                + " Does not initialize or change the repository.",
            Map.of(),
            Set.of(),
            (a, c) -> service.gitStatus()),
        tool(
            "gitDiff",
            "Read a bounded patch for one permitted text file, WORKTREE or STAGED. Fixed Git"
                + " arguments; no external diff/textconv and no shell.",
            Map.of(
                "path",
                path,
                "scope",
                Map.of("type", "string", "enum", List.of("WORKTREE", "STAGED"))),
            Set.of("path"),
            (a, c) -> service.gitDiff(string(a, "path", null), string(a, "scope", "WORKTREE"))));
  }

  private static AgentTool tool(
      String name,
      String description,
      Map<String, ?> properties,
      Set<String> required,
      BiFunction<Map<String, Object>, AgentToolContext, Object> action) {
    String schema =
        WorkspaceJson.encode(
            Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required.stream().sorted().toList(),
                "additionalProperties",
                false));
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String inputSchema() {
        return schema;
      }

      @Override
      public AgentToolResult execute(String rawArguments, AgentToolContext context) {
        try {
          if (context == null || context.runId() == null || context.conversationId() == null)
            throw new WorkspaceException("INVALID_CONTEXT");
          Map<String, Object> args =
              AgentJson.arguments(rawArguments, properties.keySet(), required);
          return AgentToolResult.success(WorkspaceJson.bounded(action.apply(args, context)));
        } catch (WorkspaceException expected) {
          return error(expected.code());
        } catch (RuntimeException invalid) {
          return error("INVALID_ARGUMENTS");
        }
      }
    };
  }

  private static AgentToolResult error(String code) {
    return AgentToolResult.error(
        code,
        WorkspaceJson.encode(
            Map.of(
                "message", "Локальное чтение не выполнено; проверьте код отказа.", "code", code)));
  }

  private static String string(Map<String, Object> args, String name, String fallback) {
    if (!args.containsKey(name)) return fallback;
    if (!(args.get(name) instanceof String value))
      throw new WorkspaceException("INVALID_ARGUMENTS");
    return value;
  }

  private static int integer(Map<String, Object> args, String name, int fallback) {
    if (!args.containsKey(name)) return fallback;
    if (!(args.get(name) instanceof Integer value))
      throw new WorkspaceException("INVALID_ARGUMENTS");
    return value;
  }
}
