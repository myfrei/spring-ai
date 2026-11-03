package dev.myfrei.repopilot.agent;

import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.tools.ToolModelIdentity;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class AgentFixtures {
  static final String DIGEST = "a".repeat(64),
      QUESTION = "PRIVATE-QUESTION: изучи TASK-101 {literal}";

  static ChatResponse response(String text, String finish, List<AssistantMessage.ToolCall> calls) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content(text).toolCalls(calls).build(),
                ChatGenerationMetadata.builder().finishReason(finish).build())),
        ChatResponseMetadata.builder().usage(new DefaultUsage(8, 3)).build());
  }

  static ChatResponse tool(String name, String args, int id) {
    return response("", "stop", List.of(call(name, args, id)));
  }

  static AssistantMessage.ToolCall call(String name, String args, int id) {
    return new AssistantMessage.ToolCall("call-" + id, "function", name, args);
  }

  static ChatResponse answer() {
    return response("Учебная задача использует порт8091.", "stop", List.of());
  }

  static class Model implements ChatModel {
    int calls;
    final List<Prompt> prompts = new ArrayList<>();
    final List<ChatResponse> script = new ArrayList<>();
    RuntimeException failure;
    Runnable hook;

    @Override
    public OllamaChatOptions getOptions() {
      return OllamaChatOptions.builder()
          .model("fixture")
          .temperature(.9)
          .numCtx(9999)
          .numPredict(999)
          .build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      calls++;
      if (hook != null) hook.run();
      if (failure != null) throw failure;
      if (script.isEmpty()) return tool("getProjectInfo", "{}", calls);
      return script.get(Math.min(calls - 1, script.size() - 1));
    }
  }

  static class Identity implements ToolModelIdentity {
    int calls;
    List<String> values = new ArrayList<>();
    RuntimeException failure;

    public String digest() {
      calls++;
      if (failure != null) throw failure;
      return values.isEmpty() ? DIGEST : values.get(Math.min(calls - 1, values.size() - 1));
    }
  }

  static AgentEngine engine(Model model, Identity identity, AgentStore store) {
    return new AgentEngine(
        model, identity, TaskAgentTools.create(new TaskCatalog()), store, "fixture", 4096);
  }

  static AgentTool custom(
      String name,
      java.util.function.BiFunction<String, AgentToolContext, AgentToolResult> action) {
    return new AgentTool() {
      public String name() {
        return name;
      }

      public String description() {
        return "Bounded fixture data";
      }

      public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
      }

      public AgentToolResult execute(String args, AgentToolContext context) {
        return action.apply(args, context);
      }
    };
  }
}
