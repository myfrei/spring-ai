package dev.myfrei.repopilot.conversation;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConversationMemoryFactory {
    private final SqliteHistory history;
    private final int maxMessages;

    public ConversationMemoryFactory(SqliteHistory history,
            @Value("${repopilot.history.max-messages}") int maxMessages) {
        if (maxMessages < 3 || maxMessages > 101 || maxMessages % 2 == 0) {
            throw new IllegalArgumentException("Use an odd message window between 3 and 101");
        }
        this.history = history;
        this.maxMessages = maxMessages;
    }

    public ChatMemory create(String id) {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(this.maxMessages).build();
        for (Turn turn : this.history.completedWindow(id, (this.maxMessages - 1) / 2)) {
            memory.add(id, new UserMessage(turn.question()));
            memory.add(id, new AssistantMessage(turn.answer()));
        }
        return memory;
    }
}
