package dev.myfrei.repopilot.circuit;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.myfrei.repopilot.mcphost.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CircuitTaskGatewayTest {
    @Test void deniedPermitDoesNotReachRemoteBoundary(){
        var calls=new AtomicInteger();var gate=new ControlledGate(false);
        var gateway=new CircuitTaskGateway(id->{calls.incrementAndGet();throw new AssertionError("No remote read expected");},gate);
        assertThatThrownBy(()->gateway.readTask("TASK-101")).isInstanceOfSatisfying(GatewayException.class,e->{assertThat(e.code()).isEqualTo("MCP_CIRCUIT_OPEN");assertThat(e.kind()).isEqualTo(GatewayException.Kind.AVAILABILITY);assertThat(e.metadata().remoteCalls()).isZero();});
        assertThat(calls).hasValue(0);assertThat(gate.outcomes).isEmpty();
    }
    @Test void onlyTransportFailureCountsAsOutageAndOriginalErrorIsPreserved(){
        for(var kind:GatewayException.Kind.values()){
            var gate=new ControlledGate(true);var calls=new AtomicInteger();var original=new GatewayException(kind,"CODE","safe",RemoteReadMetadata.empty());
            var gateway=new CircuitTaskGateway(id->{calls.incrementAndGet();throw original;},gate);
            assertThatThrownBy(()->gateway.readTask("TASK-101")).isSameAs(original);assertThat(calls).hasValue(1);
            assertThat(gate.outcomes).containsExactly(kind==GatewayException.Kind.AVAILABILITY?CircuitGate.Outcome.TRANSPORT_FAILURE:CircuitGate.Outcome.HEALTHY);
        }
    }
    @Test void successAndUnexpectedExceptionAreNotRewrittenOrRetried(){
        var gate=new ControlledGate(true);var calls=new AtomicInteger();
        var read=new ValidatedTaskRead(new TaskCatalog().getTask("TASK-101"),RemoteReadMetadata.empty());
        var gateway=new CircuitTaskGateway(id->{calls.incrementAndGet();return read;},gate);
        assertThat(gateway.readTask("TASK-101")).isSameAs(read);assertThat(calls).hasValue(1);assertThat(gate.outcomes).containsExactly(CircuitGate.Outcome.HEALTHY);
        var unexpected=new IllegalStateException("fixture");var other=new ControlledGate(true);
        assertThatThrownBy(()->new CircuitTaskGateway(id->{throw unexpected;},other).readTask("TASK-101")).isSameAs(unexpected);
        assertThat(other.outcomes).containsExactly(CircuitGate.Outcome.HEALTHY);
    }
    private static class ControlledGate extends CircuitGate {
        private final boolean allowed;final List<Outcome> outcomes=new ArrayList<>();
        ControlledGate(boolean allowed){super(3,Duration.ofSeconds(30),Clock.systemUTC());this.allowed=allowed;}
        @Override public synchronized Permit acquire(){return new Permit(1,0,allowed,allowed?State.CLOSED:State.OPEN,allowed?0:30000);}
        @Override public synchronized void complete(Permit permit,Outcome outcome){outcomes.add(outcome);}
    }
}
