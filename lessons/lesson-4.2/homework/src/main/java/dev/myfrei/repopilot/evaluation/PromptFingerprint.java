package dev.myfrei.repopilot.evaluation;

import java.util.ArrayList;
import java.util.List;
import dev.myfrei.repopilot.ingestion.Hashes;
import org.springframework.ai.chat.messages.Message;
public final class PromptFingerprint {
    private PromptFingerprint() { }
    public static String of(List<Message> messages) {
        var fields = new ArrayList<String>(); fields.add("prompt-v1");
        for (var message : messages) { fields.add(message.getMessageType().getValue()); fields.add(message.getText()); }
        return Hashes.identity(fields.toArray(String[]::new));
    }
}
