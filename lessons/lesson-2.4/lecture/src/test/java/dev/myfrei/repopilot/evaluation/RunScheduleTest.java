package dev.myfrei.repopilot.evaluation;

import java.util.List;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
class RunScheduleTest {
    @Test void everyQuestionRepetitionReceivesAllModesExactlyOnce() {
        var request = new Request(Split.DEV, List.of("dev-01", "dev-02"), null, 3, null, null, false);
        var tasks = RunSchedule.build(EvaluationFixtures.dataset().select(request), request);
        assertThat(tasks).hasSize(18); assertThat(tasks).extracting(RunSchedule.Task::ordinal).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 18).boxed().toList());
        for (int repetition = 1; repetition <= 3; repetition++) for (String id : request.caseIds()) {
            int repeat = repetition;
            assertThat(tasks.stream().filter(task -> task.repetition() == repeat && task.entry().id().equals(id)).map(RunSchedule.Task::mode))
                    .containsExactlyInAnyOrder(Mode.CHAT, Mode.FULL_CONTEXT, Mode.RAG);
        }
    }
    @Test void rotatesModesAcrossQuestionsAndRepetitionsWithoutChangingQuestion() {
        var request = new Request(Split.DEV, List.of("dev-01", "dev-02"), null, 2, null, null, false);
        var entries = EvaluationFixtures.dataset().select(request); var tasks = RunSchedule.build(entries, request);
        assertThat(tasks.subList(0, 3)).extracting(RunSchedule.Task::mode).containsExactly(Mode.CHAT, Mode.FULL_CONTEXT, Mode.RAG);
        assertThat(tasks.subList(3, 6)).extracting(RunSchedule.Task::mode).containsExactly(Mode.FULL_CONTEXT, Mode.RAG, Mode.CHAT);
        assertThat(tasks.subList(6, 9)).extracting(RunSchedule.Task::mode).containsExactly(Mode.FULL_CONTEXT, Mode.RAG, Mode.CHAT);
        assertThat(tasks.getFirst().entry()).isEqualTo(entries.getFirst());
    }
    @Test void respectsSelectedModesAndValidatesBounds() {
        var request = new Request(Split.DEV, List.of("dev-01"), List.of(Mode.RAG, Mode.CHAT), 1, 2, .7, false);
        assertThat(RunSchedule.build(EvaluationFixtures.dataset().select(request), request)).extracting(RunSchedule.Task::mode).containsExactly(Mode.RAG, Mode.CHAT);
        assertThatThrownBy(() -> new Request(null, null, List.of(Mode.CHAT, Mode.CHAT), 1, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Request(null, null, null, 4, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Request(null, null, null, 1, null, Double.NaN, false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void promptFingerprintTracksRolesOrderAndLiteralTextWithStableEncoding() {
        var messages = List.<org.springframework.ai.chat.messages.Message>of(new org.springframework.ai.chat.messages.SystemMessage("s"), new org.springframework.ai.chat.messages.UserMessage("q"));
        assertThat(PromptFingerprint.of(messages)).isEqualTo(dev.myfrei.repopilot.ingestion.Hashes.identity("prompt-v1", "system", "s", "user", "q"));
        assertThat(PromptFingerprint.of(List.of(messages.getLast(), messages.getFirst()))).isNotEqualTo(PromptFingerprint.of(messages));
    }
}
