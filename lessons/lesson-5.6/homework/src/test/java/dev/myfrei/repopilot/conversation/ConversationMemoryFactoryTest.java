package dev.myfrei.repopilot.conversation;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import static org.assertj.core.api.Assertions.*;

class ConversationMemoryFactoryTest {
    @TempDir Path directory;
    private SqliteHistory history;
    @BeforeEach void setup() { history = new SqliteHistory(directory.resolve("chat.db").toString()); }

    @Test void hydratesOnlyOwnCompletedPairsWithCorrectRoles() {
        var a = history.create("A"); var b = history.create("B");
        add(a.id(), "Меня зовут Анна", "Привет, Анна", "completed");
        add(b.id(), "Секрет Бориса", "Ответ Борису", "completed");
        add(a.id(), "Неудачный", "Обрыв", "failed");
        add(a.id(), "Отмена", "Часть", "cancelled");
        var memory = new ConversationMemoryFactory(history, 7).create(a.id());
        assertThat(memory.get(a.id())).extracting(Message::getText).containsExactly("Меня зовут Анна", "Привет, Анна");
        assertThat(memory.get(a.id())).extracting(m -> m.getMessageType().getValue()).containsExactly("user", "assistant");
        assertThat(memory.get(b.id())).isEmpty();
    }

    @Test void currentQuestionFitsWindowWhileFullHistoryKeepsGrowing() {
        var a = history.create("A");
        for (int i = 1; i <= 5; i++) add(a.id(), "Q" + i, "A" + i, "completed");
        var memory = new ConversationMemoryFactory(history, 7).create(a.id());
        memory.add(a.id(), new UserMessage("Текущий"));
        assertThat(memory.get(a.id())).extracting(Message::getText).containsExactly("Q3", "A3", "Q4", "A4", "Q5", "A5", "Текущий");
        assertThat(history.turns(a.id())).hasSize(5);
    }

    @Test void ephemeralAdvisorWritesDoNotChangeCanonicalHistory() {
        var a = history.create("A"); add(a.id(), "Q", "A", "completed");
        var factory = new ConversationMemoryFactory(history, 7);
        factory.create(a.id()).add(a.id(), new UserMessage("Несохранённый"));
        assertThat(factory.create(a.id()).get(a.id())).hasSize(2);
        assertThat(history.turns(a.id())).hasSize(1);
    }

    @Test void validatesWindowConfiguration() {
        for (int n : new int[] {0, 2, 6, 103}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ConversationMemoryFactory(history, n));
        }
    }

    private void add(String id, String question, String answer, String status) {
        long turn = history.begin(id, question); history.append(turn, answer); history.finish(turn, status, "");
    }
}
