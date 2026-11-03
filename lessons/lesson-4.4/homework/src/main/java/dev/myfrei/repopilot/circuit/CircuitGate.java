package dev.myfrei.repopilot.circuit;

import java.time.*;
import java.util.*;

/** Local per-server availability policy. No network, scheduling, retries or persistence. */
public class CircuitGate {
    public enum State { CLOSED,OPEN,HALF_OPEN }
    public enum Outcome { HEALTHY,TRANSPORT_FAILURE }
    public record Permit(long id,long generation,boolean allowed,State state,long retryAfterMs) { }
    public record Snapshot(State state,int failures,Instant retryAt,boolean probeInFlight) { }
    private final int threshold;
    private final Duration cooldown;
    private final Clock clock;
    private final Set<Long> inFlight=new HashSet<>();
    private State state=State.CLOSED;
    private int failures;
    private Instant retryAt=Instant.EPOCH;
    private boolean probeInFlight;
    private long generation,nextId;
    public CircuitGate(int threshold,Duration cooldown,Clock clock){
        if(threshold<1||threshold>10||cooldown==null||cooldown.isZero()||cooldown.isNegative()||cooldown.compareTo(Duration.ofMinutes(5))>0||clock==null)throw new IllegalArgumentException("Invalid circuit configuration");
        this.threshold=threshold;this.cooldown=cooldown;this.clock=clock;
    }
    public synchronized Permit acquire(){
        // TODO HW4.4: fail fast while open; exactly one half-open probe after cooldown.
        throw new UnsupportedOperationException("HW4.4: acquire circuit permit");
    }
    public synchronized void complete(Permit permit,Outcome outcome){
        // TODO HW4.4: single-use completion, generation guard and state transitions.
        throw new UnsupportedOperationException("HW4.4: record circuit outcome");
    }
    public synchronized Snapshot snapshot(){return new Snapshot(state,failures,retryAt,probeInFlight);}
}
