package dev.myfrei.repopilot.agent;

import java.util.*;
import org.springframework.ai.chat.messages.*;

/** Explicit local raw transcript DTO; never included in metadata events or reports. */
public record AgentMessage(
    String role, String text, List<ToolCall> toolCalls, List<ToolReply> toolResults) {
  public AgentMessage {
    toolCalls = List.copyOf(toolCalls);
    toolResults = List.copyOf(toolResults);
  }

  public record ToolCall(String id, String type, String name, String arguments) {}

  public record ToolReply(String id, String name, String resultJson) {}

  static AgentMessage from(Message message) {
    var calls =
        message instanceof AssistantMessage a
            ? a.getToolCalls().stream()
                .map(c -> new ToolCall(c.id(), c.type(), c.name(), c.arguments()))
                .toList()
            : List.<ToolCall>of();
    var results =
        message instanceof ToolResponseMessage r
            ? r.getResponses().stream()
                .map(c -> new ToolReply(c.id(), c.name(), c.responseData()))
                .toList()
            : List.<ToolReply>of();
    return new AgentMessage(
        message.getMessageType().name(),
        message.getText() == null ? "" : message.getText(),
        calls,
        results);
  }
}
