package dev.myfrei.repopilot.context;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

public record ContextPlan(List<Message> messages, ContextReport report) {
    public ContextPlan { messages = List.copyOf(messages); }
}
