package dev.myfrei.repopilot.context;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

@Component
public class Utf8TokenEstimator {
    public static final String NAME = "utf8-div3-overhead12-v1";

    public int estimate(List<Message> messages) {
        int estimate = 8; // Учебная оценка служебного хвоста chat template.
        for (Message message : messages) {
            int bytes = message.getText().getBytes(StandardCharsets.UTF_8).length;
            estimate = Math.addExact(estimate, 12 + (bytes + 2) / 3);
        }
        return estimate;
    }
}
