package dev.myfrei.repopilot.circuit;

import java.util.Objects;
import dev.myfrei.repopilot.mcphost.*;

/** Provided decorator: availability policy cannot weaken MCP payload validation. */
public final class CircuitTaskGateway implements RemoteTaskGateway {
    private final RemoteTaskGateway delegate;
    private final CircuitGate gate;
    public CircuitTaskGateway(RemoteTaskGateway delegate,CircuitGate gate){this.delegate=Objects.requireNonNull(delegate);this.gate=Objects.requireNonNull(gate);}
    @Override public ValidatedTaskRead readTask(String requestedId){
        var permit=gate.acquire();
        if(!permit.allowed())throw new GatewayException(GatewayException.Kind.AVAILABILITY,"MCP_CIRCUIT_OPEN",
                "MCP circuit открыт или probe уже выполняется; запрос завершён без удалённого вызова.",RemoteReadMetadata.empty());
        try{
            var result=delegate.readTask(requestedId);gate.complete(permit,CircuitGate.Outcome.HEALTHY);return result;
        }catch(GatewayException error){
            gate.complete(permit,error.kind()==GatewayException.Kind.AVAILABILITY?CircuitGate.Outcome.TRANSPORT_FAILURE:CircuitGate.Outcome.HEALTHY);
            throw error;
        }catch(RuntimeException error){gate.complete(permit,CircuitGate.Outcome.HEALTHY);throw error;}
    }
}
