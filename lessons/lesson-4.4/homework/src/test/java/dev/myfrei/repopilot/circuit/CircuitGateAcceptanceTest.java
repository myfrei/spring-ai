package dev.myfrei.repopilot.circuit;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static dev.myfrei.repopilot.circuit.CircuitGate.*;

class CircuitGateAcceptanceTest {
    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-10-31T00:00:00Z");
        void advance(long seconds){now=now.plusSeconds(seconds);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    private CircuitGate gate(MutableClock clock){return new CircuitGate(3,Duration.ofSeconds(30),clock);}
    private void fail(CircuitGate gate){gate.complete(gate.acquire(),Outcome.TRANSPORT_FAILURE);}
    private void open(CircuitGate gate){fail(gate);fail(gate);fail(gate);}
    @Test void closedAllowsRequestsAndHealthyResultResetsConsecutiveFailures(){
        var clock=new MutableClock();var gate=gate(clock);fail(gate);fail(gate);
        assertThat(gate.snapshot().state()).isEqualTo(State.CLOSED);assertThat(gate.snapshot().failures()).isEqualTo(2);
        var permit=gate.acquire();assertThat(permit.allowed()).isTrue();gate.complete(permit,Outcome.HEALTHY);
        assertThat(gate.snapshot().failures()).isZero();fail(gate);assertThat(gate.snapshot().state()).isEqualTo(State.CLOSED);
    }
    @Test void thresholdOpensAndFailsFastUntilInclusiveCooldownBoundary(){
        var clock=new MutableClock();var gate=gate(clock);open(gate);
        assertThat(gate.snapshot().state()).isEqualTo(State.OPEN);assertThat(gate.snapshot().retryAt()).isEqualTo(clock.instant().plusSeconds(30));
        var blocked=gate.acquire();assertThat(blocked.allowed()).isFalse();assertThat(blocked.retryAfterMs()).isEqualTo(30000);
        clock.advance(29);assertThat(gate.acquire().allowed()).isFalse();clock.advance(1);
        var probe=gate.acquire();assertThat(probe.allowed()).isTrue();assertThat(probe.state()).isEqualTo(State.HALF_OPEN);assertThat(gate.acquire().allowed()).isFalse();
    }
    @Test void healthyProbeClosesAndReopensNormalAdmission(){
        var clock=new MutableClock();var gate=gate(clock);open(gate);clock.advance(30);var probe=gate.acquire();
        assertThat(gate.snapshot().probeInFlight()).isTrue();gate.complete(probe,Outcome.HEALTHY);
        assertThat(gate.snapshot().state()).isEqualTo(State.CLOSED);assertThat(gate.snapshot().failures()).isZero();assertThat(gate.snapshot().probeInFlight()).isFalse();
        assertThat(gate.acquire().allowed()).isTrue();assertThat(gate.acquire().allowed()).isTrue();
    }
    @Test void failedProbeRestartsCooldownAtCompletionNotAdmission(){
        var clock=new MutableClock();var gate=gate(clock);open(gate);clock.advance(30);var probe=gate.acquire();clock.advance(5);
        gate.complete(probe,Outcome.TRANSPORT_FAILURE);assertThat(gate.snapshot().state()).isEqualTo(State.OPEN);
        assertThat(gate.snapshot().retryAt()).isEqualTo(clock.instant().plusSeconds(30));clock.advance(29);assertThat(gate.acquire().allowed()).isFalse();
    }
    @Test void permitCompletionIsSingleUseAndStaleClosedCallsCannotResetNewOpenState(){
        var clock=new MutableClock();var gate=gate(clock);var stale=gate.acquire();var lateFailure=gate.acquire();var first=gate.acquire();gate.complete(first,Outcome.TRANSPORT_FAILURE);gate.complete(first,Outcome.TRANSPORT_FAILURE);
        assertThat(gate.snapshot().failures()).isEqualTo(1);fail(gate);fail(gate);assertThat(gate.snapshot().state()).isEqualTo(State.OPEN);
        var openState=gate.snapshot();gate.complete(stale,Outcome.HEALTHY);assertThat(gate.snapshot()).isEqualTo(openState);
        clock.advance(30);var probe=gate.acquire();gate.complete(probe,Outcome.HEALTHY);gate.complete(probe,Outcome.TRANSPORT_FAILURE);
        assertThat(gate.snapshot().state()).isEqualTo(State.CLOSED);assertThat(gate.snapshot().failures()).isZero();
        var recovered=gate.snapshot();gate.complete(lateFailure,Outcome.TRANSPORT_FAILURE);assertThat(gate.snapshot()).isEqualTo(recovered);
    }
    @Test void exactlyOneConcurrentHalfOpenProbeIsGranted() throws Exception{
        var clock=new MutableClock();var gate=gate(clock);open(gate);clock.advance(30);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<Permit>>();for(int i=0;i<16;i++)futures.add(executor.submit(()->{start.await();return gate.acquire();}));start.countDown();
            var permits=new ArrayList<Permit>();for(var future:futures)permits.add(future.get(5,TimeUnit.SECONDS));
            assertThat(permits.stream().filter(Permit::allowed).count()).isEqualTo(1);assertThat(gate.snapshot().probeInFlight()).isTrue();
        }
    }
    @Test void separateServersDoNotShareStateAndClockRollbackCannotOpenAdmission(){
        var clock=new MutableClock();var first=gate(clock);var second=gate(clock);open(first);assertThat(second.acquire().allowed()).isTrue();
        clock.advance(-10);assertThat(first.acquire().allowed()).isFalse();assertThat(first.acquire().retryAfterMs()).isGreaterThanOrEqualTo(30000);
    }
    @Test void deniedPermitHasNoEffectAndInvalidConfigurationIsRejected(){
        var clock=new MutableClock();var gate=gate(clock);open(gate);var denied=gate.acquire();var before=gate.snapshot();gate.complete(denied,Outcome.HEALTHY);assertThat(gate.snapshot()).isEqualTo(before);
        assertThatIllegalArgumentException().isThrownBy(()->new CircuitGate(0,Duration.ofSeconds(1),clock));
        assertThatIllegalArgumentException().isThrownBy(()->new CircuitGate(11,Duration.ofSeconds(1),clock));
        assertThatIllegalArgumentException().isThrownBy(()->new CircuitGate(3,Duration.ZERO,clock));
        assertThatIllegalArgumentException().isThrownBy(()->new CircuitGate(3,Duration.ofMinutes(6),clock));
    }
}
