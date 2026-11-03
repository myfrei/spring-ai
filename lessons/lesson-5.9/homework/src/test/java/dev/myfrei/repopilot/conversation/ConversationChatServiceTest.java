package dev.myfrei.repopilot.conversation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.myfrei.repopilot.chat.ChatStreamEvent;
import dev.myfrei.repopilot.chat.ChatStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationChatServiceTest {
    @TempDir Path directory;
    private SqliteHistory history;
    private ChatStreamService model;
    private ConversationChatService service;
    private String id;

    @BeforeEach void setup() {
        history = spy(new SqliteHistory(directory.resolve("chat.db").toString()));
        model = mock(ChatStreamService.class);
        service = new ConversationChatService(history, new ConversationGate(), new ConversationMemoryFactory(history, 7), model);
        id = history.create("Java").id();
    }

    @Test void startsLazilyAndPersistsCompletedBeforeDeliveringTerminal() {
        when(model.stream(eq("Вопрос"), any(ChatMemory.class), eq(id))).thenReturn(Flux.just(
                ChatStreamEvent.started(), ChatStreamEvent.delta("Часть "), ChatStreamEvent.delta("ответа"), ChatStreamEvent.completed()));
        var stream = service.stream(id, "Вопрос");
        assertThat(history.turns(id)).isEmpty();
        StepVerifier.create(stream).expectNext(ChatStreamEvent.started()).expectNextCount(2)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("completed");
                    assertThat(history.turns(id).getFirst().status()).isEqualTo("completed");
                }).verifyComplete();
        assertThat(history.turns(id).getFirst().answer()).isEqualTo("Часть ответа");
    }

    @Test void failedPreservesPartialAndIsExcludedFromNextMemory() {
        when(model.stream(anyString(), any(ChatMemory.class), eq(id))).thenReturn(Flux.just(
                ChatStreamEvent.started(), ChatStreamEvent.delta("Часть"), ChatStreamEvent.failed("MODEL_ERROR", "Обрыв")));
        service.stream(id, "Вопрос").blockLast(Duration.ofSeconds(5));
        assertThat(history.turns(id).getFirst().status()).isEqualTo("failed");
        assertThat(history.turns(id).getFirst().answer()).isEqualTo("Часть");
        assertThat(new ConversationMemoryFactory(history, 7).create(id).get(id)).isEmpty();
    }

    @Test void cancellationPersistsPartialReleasesLeaseAndCancelsUpstream() throws Exception {
        var cancelled = new CountDownLatch(1);
        when(model.stream(anyString(), any(ChatMemory.class), eq(id))).thenReturn(
                Flux.concat(Flux.just(ChatStreamEvent.started(), ChatStreamEvent.delta("Часть")), Flux.never())
                        .doOnCancel(cancelled::countDown));
        StepVerifier.create(service.stream(id, "Вопрос")).expectNext(ChatStreamEvent.started(), ChatStreamEvent.delta("Часть"))
                .thenCancel().verify(Duration.ofSeconds(5));
        assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
        verify(history, timeout(3000)).finish(anyLong(), eq("cancelled"), eq("CLIENT_CANCELLED"));
        assertThat(history.turns(id).getFirst().status()).isEqualTo("cancelled");
        when(model.stream(anyString(), any(ChatMemory.class), eq(id))).thenReturn(Flux.just(ChatStreamEvent.delta("OK"), ChatStreamEvent.completed()));
        assertThat(service.stream(id, "Следующий").collectList().block(Duration.ofSeconds(5)))
                .extracting(ChatStreamEvent::type).containsExactly("started", "delta", "completed");
    }

    @Test void busyConversationProducesFailedWithoutSecondDatabaseTurn() throws Exception {
        var opened = new CountDownLatch(1);
        when(model.stream(anyString(), any(ChatMemory.class), eq(id))).thenReturn(Flux.<ChatStreamEvent>never().doOnSubscribe(s -> opened.countDown()));
        var first = service.stream(id, "Первый").subscribe();
        try {
            assertThat(opened.await(3, TimeUnit.SECONDS)).isTrue();
            var rejected = service.stream(id, "Второй").collectList().block(Duration.ofSeconds(5));
            assertThat(rejected).extracting(ChatStreamEvent::type).containsExactly("started", "failed");
            assertThat(rejected.getLast().code()).isEqualTo("CONVERSATION_BUSY");
            assertThat(history.turns(id)).hasSize(1);
        } finally {
            first.dispose();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(history.turns(id).getFirst().status()).isEqualTo("cancelled"));
        }
    }

    @Test void missingConversationReleasesGateAndReturnsSafeFailure() {
        var events = service.stream("missing", "Вопрос").collectList().block(Duration.ofSeconds(5));
        assertThat(events.getLast().code()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(model);
    }
}
