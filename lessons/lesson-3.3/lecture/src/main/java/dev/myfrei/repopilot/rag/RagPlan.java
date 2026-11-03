package dev.myfrei.repopilot.rag;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;

public record RagPlan(List<Message> baseMessages, List<Document> references, RagReport report) {
    public RagPlan { baseMessages = List.copyOf(baseMessages); references = List.copyOf(references); }
}
