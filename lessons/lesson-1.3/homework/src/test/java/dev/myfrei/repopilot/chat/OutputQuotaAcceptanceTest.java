package dev.myfrei.repopilot.chat;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OutputQuotaAcceptanceTest {
    private final OutputQuota quota=new OutputQuota();
    @Test void truncationIsAFailedTerminalAndCancelsSource() {
        var cancelled=new AtomicBoolean();
        var source=Flux.concat(Flux.just(ChatStreamEvent.started(),ChatStreamEvent.delta("ab"),ChatStreamEvent.delta("cdef")),Flux.<ChatStreamEvent>never()).doOnCancel(()->cancelled.set(true));
        StepVerifier.create(quota.apply(source,4)).expectNext(ChatStreamEvent.started(),ChatStreamEvent.delta("ab"),ChatStreamEvent.delta("cd"))
          .expectNextMatches(event->event.type().equals("failed")&&event.code().equals("OUTPUT_BUDGET")).verifyComplete();
        assertThat(cancelled).isTrue();
    }
    @Test void exactBudgetAndNormalFinishStayCompleted() {
        StepVerifier.create(quota.apply(Flux.just(ChatStreamEvent.started(),ChatStreamEvent.delta("ab"),ChatStreamEvent.completed()),2))
          .expectNext(ChatStreamEvent.started(),ChatStreamEvent.delta("ab"),ChatStreamEvent.completed()).verifyComplete();
    }
    @Test void emptyRemainingBudgetDoesNotEmitAnEmptyExtraDelta() {
        StepVerifier.create(quota.apply(Flux.just(ChatStreamEvent.started(),ChatStreamEvent.delta("ab"),ChatStreamEvent.delta("x"),ChatStreamEvent.completed()),2))
          .expectNext(ChatStreamEvent.started(),ChatStreamEvent.delta("ab")).expectNextMatches(event->event.code().equals("OUTPUT_BUDGET")).verifyComplete();
    }
    @Test void emojiIsCountedAsOneCodePointAndNeverCutInHalf() {
        StepVerifier.create(quota.apply(Flux.just(ChatStreamEvent.started(),ChatStreamEvent.delta("😀x"),ChatStreamEvent.completed()),1))
          .expectNext(ChatStreamEvent.started(),ChatStreamEvent.delta("😀")).expectNextMatches(event->event.code().equals("OUTPUT_BUDGET")).verifyComplete();
    }
    @Test void providerFailureIsPreservedAndStateIsPerSubscription() {
        var stream=quota.apply(Flux.just(ChatStreamEvent.started(),ChatStreamEvent.delta("abc"),ChatStreamEvent.failed("MODEL_ERROR","offline")),5);
        for(int i=0;i<2;i++) StepVerifier.create(stream).expectNext(ChatStreamEvent.started(),ChatStreamEvent.delta("abc"),ChatStreamEvent.failed("MODEL_ERROR","offline")).verifyComplete();
    }
    @Test void controllerUsesQuotaAndKeepsStreamingHeaders() {
        var model=mock(ChatStreamService.class);when(model.stream("Q")).thenReturn(Flux.just(ChatStreamEvent.started(),ChatStreamEvent.delta("long"),ChatStreamEvent.completed()));
        var response=new LimitedChatStreamController(model,quota,Duration.ofSeconds(10)).stream(new LimitedChatStreamController.Request("Q",2));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        StepVerifier.create(response.getBody()).expectNextMatches(frame->frame.event().equals("started")).expectNextMatches(frame->frame.data().text().equals("lo"))
          .expectNextMatches(frame->frame.data().code().equals("OUTPUT_BUDGET")).verifyComplete();
    }
    @Test void invalidLimitsAreRejectedInsteadOfDisablingSafety() {
        for(int limit:new int[]{0,-1,6001}) assertThatThrownBy(()->quota.apply(Flux.empty(),limit)).isInstanceOf(IllegalArgumentException.class);
    }
}
