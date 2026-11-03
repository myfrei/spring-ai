package dev.myfrei.repopilot.chat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatStreamControllerTest {
    @Test
    void setsStreamingHeadersAndStopsHeartbeatAfterTerminalEvent() {
        var service = mock(ChatStreamService.class);
        when(service.stream("Вопрос")).thenReturn(Flux.just(ChatStreamEvent.started(),
                ChatStreamEvent.delta("Ответ"), ChatStreamEvent.completed()));
        var response = new ChatStreamController(service, Duration.ofSeconds(10)).stream(new ChatRequest("Вопрос"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        StepVerifier.create(response.getBody()).expectNextMatches(e -> e.event().equals("started"))
                .expectNextMatches(e -> e.event().equals("delta"))
                .expectNextMatches(e -> e.event().equals("completed")).verifyComplete();
    }

    @Test
    void heartbeatsAreCommentsAndCancellationReleasesSource() {
        var cancelled = new AtomicBoolean();
        var service = mock(ChatStreamService.class);
        when(service.stream("Вопрос")).thenReturn(Flux.concat(Flux.just(ChatStreamEvent.started()),
                Flux.<ChatStreamEvent>never()).doOnCancel(() -> cancelled.set(true)));
        StepVerifier.withVirtualTime(() -> new ChatStreamController(service, Duration.ofSeconds(1))
                        .stream(new ChatRequest("Вопрос")).getBody())
                .expectNextMatches(e -> e.event().equals("started"))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextMatches(e -> e.data() == null && "keepalive".equals(e.comment()))
                .thenCancel().verify();
        assertThat(cancelled).isTrue();
    }
}
