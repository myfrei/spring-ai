package dev.myfrei.repopilot.readiness;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static dev.myfrei.repopilot.readiness.ReleaseReadiness.*;

class ReleaseReadinessAcceptanceTest {
    private final ReleaseReadinessPolicy policy=new ReleaseReadinessPolicy();
    private ReadinessInput.Event event(String name,int revision,int attempt,String status){return new ReadinessInput.Event(name,revision,attempt,status);}
    private List<ReadinessInput.Event> passed(){return List.of(event("BUILD",2,1,"PASS"),event("UNIT",2,1,"PASS"),event("INTEGRATION",2,1,"PASS"));}
    private ReleaseReadiness assess(String status,List<ReadinessInput.Event> events){return policy.assess(new ReadinessInput("TASK-105",status,2,events));}
    @Test void doneAndEveryCurrentRequiredCheckPassingIsReady(){
        var result=assess("DONE",passed());assertThat(result.verdict()).isEqualTo(Verdict.READY);assertThat(result.reasons()).isEmpty();
        assertThat(result.origin()).isEqualTo("COURSE_FIXTURE");assertThat(result.taskId()).isEqualTo("TASK-105");assertThat(result.revision()).isEqualTo(2);
        assertThat(result.checks()).extracting(Check::name).containsExactly("BUILD","UNIT","INTEGRATION");assertThat(result.checks()).extracting(Check::state).containsOnly(CheckState.PASS);
    }
    @Test void openTaskOrFailedCurrentCheckBlocksReadiness(){
        assertThat(assess("OPEN",passed()).verdict()).isEqualTo(Verdict.BLOCKED);assertThat(assess("IN_PROGRESS",passed()).reasons()).contains("TASK_NOT_DONE");
        var events=new ArrayList<>(passed());events.add(event("UNIT",2,2,"FAIL"));
        var blocked=assess("DONE",events);assertThat(blocked.verdict()).isEqualTo(Verdict.BLOCKED);assertThat(blocked.reasons()).contains("FAILED_CHECK");
    }
    @Test void staleNewerAndOlderRevisionsNeverProvideCurrentEvidence(){
        var result=assess("DONE",List.of(event("BUILD",2,1,"PASS"),event("UNIT",1,99,"PASS"),event("INTEGRATION",3,99,"PASS")));
        assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);assertThat(result.ignoredStaleResults()).isEqualTo(2);
        assertThat(result.checks()).extracting(Check::state).containsExactly(CheckState.PASS,CheckState.MISSING,CheckState.MISSING);
        assertThat(result.checks()).extracting(Check::attempt).containsExactly(1,0,0);
    }
    @Test void latestAttemptWinsAndUnknownCannotReuseEarlierSuccess(){
        var events=new ArrayList<>(passed());events.add(event("UNIT",2,2,"FAIL"));events.add(event("UNIT",2,3,"PASS"));
        var recovered=assess("DONE",events);assertThat(recovered.verdict()).isEqualTo(Verdict.READY);assertThat(recovered.checks().get(1).attempt()).isEqualTo(3);
        events.add(event("UNIT",2,4,"UNKNOWN"));assertThat(assess("DONE",events).verdict()).isEqualTo(Verdict.UNKNOWN);
    }
    @Test void conflictingLatestStatusesAreUnknownButExactDuplicatesAreHarmless(){
        var events=new ArrayList<>(passed());events.add(event("UNIT",2,1,"PASS"));assertThat(assess("DONE",events).verdict()).isEqualTo(Verdict.READY);
        events.add(event("UNIT",2,1,"FAIL"));var conflict=assess("DONE",events);
        assertThat(conflict.verdict()).isEqualTo(Verdict.UNKNOWN);assertThat(conflict.checks().get(1).state()).isEqualTo(CheckState.CONFLICT);assertThat(conflict.reasons()).contains("CONFLICTING_CHECKS");
        events.add(event("INTEGRATION",2,2,"FAIL"));assertThat(assess("DONE",events).verdict()).isEqualTo(Verdict.BLOCKED);
    }
    @Test void arrivalOrderDoesNotChangeLatestResultsOrStableOutput(){
        var events=new ArrayList<>(passed());events.add(event("BUILD",2,2,"FAIL"));events.add(event("BUILD",2,3,"PASS"));events.add(event("UNIT",1,5,"FAIL"));
        var expected=assess("DONE",events);Collections.reverse(events);assertThat(assess("DONE",events)).isEqualTo(expected);
    }
    @Test void emptyEvidenceNeverBecomesReadyAndInputOutputAreImmutable(){
        var result=assess("DONE",List.of());assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);assertThat(result.checks()).extracting(Check::state).containsOnly(CheckState.MISSING);
        assertThat(result.reasons()).contains("INCOMPLETE_CHECKS");assertThatThrownBy(()->result.checks().clear()).isInstanceOf(UnsupportedOperationException.class);
        var original=new ArrayList<>(passed());var input=new ReadinessInput("TASK-105","DONE",2,original);original.clear();assertThat(policy.assess(input).verdict()).isEqualTo(Verdict.READY);
    }
    @Test void failedAndUnfinishedReasonsCanCoexistAndBoundsAreValidated(){
        var events=new ArrayList<>(passed());events.add(event("BUILD",2,2,"FAIL"));var result=assess("OPEN",events);
        assertThat(result.verdict()).isEqualTo(Verdict.BLOCKED);assertThat(result.reasons()).contains("TASK_NOT_DONE","FAILED_CHECK");
        assertThatIllegalArgumentException().isThrownBy(()->new ReadinessInput("../secret","DONE",2,List.of()));
        assertThatIllegalArgumentException().isThrownBy(()->event("OTHER",2,1,"PASS"));assertThatIllegalArgumentException().isThrownBy(()->event("UNIT",2,0,"PASS"));
        assertThatIllegalArgumentException().isThrownBy(()->new ReadinessInput("TASK-105","DONE",0,List.of()));
    }
}
