package dev.myfrei.repopilot.explanation;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
@Component
public class ExplanationPolicy {
    public List<Message> messages(String message, ExplanationMode mode) {
        // TODO HW-1: независимый режим объяснения, валидный вопрос и отдельные роли.
        throw new UnsupportedOperationException("TODO HW-1: explanation policy");
    }
}
