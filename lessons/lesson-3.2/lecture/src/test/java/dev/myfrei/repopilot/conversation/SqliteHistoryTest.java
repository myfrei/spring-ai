package dev.myfrei.repopilot.conversation;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SqliteHistoryTest {
    @TempDir Path directory;
    private Path database;
    private SqliteHistory history;

    @BeforeEach void setup() {
        this.database = directory.resolve("nested/chat.db");
        this.history = new SqliteHistory(this.database.toString());
    }

    @Test void createsDistinctConversationsAndListsThem() {
        var first = history.create("  Java  "); var second = history.create("Spring");
        assertThat(first.title()).isEqualTo("Java");
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(history.list()).extracting(Conversation::id).containsExactly(second.id(), first.id());
        assertThat(history.get(first.id())).isEqualTo(first);
    }

    @Test void rejectsBlankAndTooLongTitles() {
        assertThatIllegalArgumentException().isThrownBy(() -> history.create(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> history.create("x".repeat(81)));
    }

    @Test void storesLiteralSqlAndPreservesDeltaWhitespace() {
        var c = history.create("'; DROP TABLE turns;--");
        long id = history.begin(c.id(), "  Как устроен record?  ");
        history.append(id, "class X {\n"); history.append(id, "  int x;\n}");
        history.finish(id, "completed", "");
        assertThat(history.turns(c.id())).singleElement().satisfies(turn -> {
            assertThat(turn.question()).isEqualTo("Как устроен record?");
            assertThat(turn.answer()).isEqualTo("class X {\n  int x;\n}");
            assertThat(turn.status()).isEqualTo("completed");
        });
        assertThat(history.list()).hasSize(1);
    }

    @Test void terminalUpdateCannotOverwriteCompletedOrAppendLateDelta() {
        var c = history.create("Java"); long id = completed(c.id(), "Вопрос", "Ответ");
        history.finish(id, "cancelled", "CLIENT_CANCELLED"); history.append(id, "Поздний текст");
        assertThat(history.turns(c.id()).getFirst().status()).isEqualTo("completed");
        assertThat(history.turns(c.id()).getFirst().answer()).isEqualTo("Ответ");
    }

    @Test void restartKeepsCompleteAndMarksPendingInterruptedWithPartial() {
        var c = history.create("Java"); completed(c.id(), "Первый", "Готово");
        long pending = history.begin(c.id(), "Второй"); history.append(pending, "Часть");
        var restarted = new SqliteHistory(database.toString());
        var turns = restarted.turns(c.id());
        assertThat(turns).extracting(Turn::status).containsExactly("completed", "interrupted");
        assertThat(turns.getLast().answer()).isEqualTo("Часть");
        assertThat(turns.getLast().errorCode()).isEqualTo("RESTART");
        assertThat(restarted.list()).hasSize(1);
    }

    @Test void windowIsOrderedBoundedAndExcludesFailedAndCancelled() {
        var c = history.create("Java");
        completed(c.id(), "Q1", "A1"); completed(c.id(), "Q2", "A2");
        long failed = history.begin(c.id(), "Bad"); history.finish(failed, "failed", "MODEL_ERROR");
        long cancelled = history.begin(c.id(), "Stop"); history.finish(cancelled, "cancelled", "CLIENT_CANCELLED");
        completed(c.id(), "Q3", "A3");
        assertThat(history.completedWindow(c.id(), 2)).extracting(Turn::question).containsExactly("Q2", "Q3");
        assertThat(history.turns(c.id())).hasSize(5);
    }

    @Test void missingConversationDoesNotCreateOrphanTurn() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> history.begin(id, "Вопрос")).isInstanceOf(ConversationProblem.class);
        assertThatThrownBy(() -> history.turns(id)).isInstanceOf(ConversationProblem.class);
        assertThat(history.list()).isEmpty();
    }

    @Test void secondPendingTurnRollsBackWithoutChangingHistory() {
        var c = history.create("Java"); history.begin(c.id(), "Первый");
        assertThatIllegalStateException().isThrownBy(() -> history.begin(c.id(), "Второй"));
        assertThat(history.turns(c.id())).extracting(Turn::question).containsExactly("Первый");
    }

    @Test void rejectsInvalidQuestionAndNonterminalFinish() {
        var c = history.create("Java");
        assertThatIllegalArgumentException().isThrownBy(() -> history.begin(c.id(), " "));
        assertThatIllegalArgumentException().isThrownBy(() -> history.begin(c.id(), "x".repeat(4001)));
        assertThatIllegalArgumentException().isThrownBy(() -> history.finish(1, "pending", ""));
        assertThat(history.turns(c.id())).isEmpty();
    }

    private long completed(String id, String question, String answer) {
        long turn = history.begin(id, question); history.append(turn, answer); history.finish(turn, "completed", ""); return turn;
    }
}
