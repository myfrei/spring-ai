package dev.myfrei.repopilot.conversation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConversationGateTest {
    @Test void rejectsSameConversationAndAllowsAnother() {
        var gate = new ConversationGate();
        try (var a = gate.acquire("A"); var b = gate.acquire("B")) {
            assertThatThrownBy(() -> gate.acquire("A")).isInstanceOf(ConversationProblem.class)
                    .extracting("code").isEqualTo("CONVERSATION_BUSY");
        }
        try (var again = gate.acquire("A")) { assertThat(again).isNotNull(); }
    }

    @Test void doubleCloseCannotReleaseLaterLease() {
        var gate = new ConversationGate(); var first = gate.acquire("A"); first.close();
        try (var second = gate.acquire("A")) {
            first.close();
            assertThatThrownBy(() -> gate.acquire("A")).isInstanceOf(ConversationProblem.class);
        }
    }
}
