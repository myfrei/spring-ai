package dev.myfrei.repopilot.homework;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.evaluation.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import static org.assertj.core.api.Assertions.*;
class PairedRegressionHomeworkAcceptanceTest {
    private Conditions conditions(String version) { return new Conditions(version,"corpus",new ChatProfile("local","digest",.2,4096,256,"system"),null,"estimator",3584,"pipeline","NOT_MEASURED","rotate"); }
    private Result row(int ordinal,String id,int repeat,Outcome outcome,double time) { return new Result(ordinal,id,repeat,Mode.RAG,"Q",outcome,"A",outcome==Outcome.FAILED?"MODEL_ERROR":"",new Timing(time,1,1,outcome==Outcome.NO_EVIDENCE?null:5.0,null,null),100,null,null,1,List.of(),true,true,"hash"); }
    private Experiment run(List<Result> rows,Map<Integer,Judgment> judgments,String version) { return new Experiment(UUID.randomUUID(),"date","done",State.COMPLETED,new Request(Split.DEV,List.of(),List.of(Mode.RAG),1,4,.35,false),conditions(version),rows.size(),List.of(),rows,judgments,""); }
    private Judgment grade(int answer,int citations) { return new Judgment(answer,citations,null,"manual",""); }
    @Test void findsCaseRegressionEvenWhenMeanQualityImproves() {
        var a=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,50),row(1,"dev-02",1,Outcome.COMPLETED,50)),Map.of(0,grade(2,2),1,grade(0,0)),"dataset");
        var b=run(List.of(row(1,"dev-02",1,Outcome.COMPLETED,20),row(0,"dev-01",1,Outcome.COMPLETED,20)),Map.of(0,grade(1,2),1,grade(2,2)),"dataset");
        var report=new PairedRunComparator().compare(a,b,Mode.RAG);assertThat(report.regressions()).isEqualTo(1);assertThat(report.improvements()).isEqualTo(1);assertThat(report.qualityCompared()).isEqualTo(2);
        assertThat(report.pairs().getFirst().caseId()).isEqualTo("dev-01");assertThat(report.pairs().getFirst().answerDelta()).isEqualTo(-1);assertThat(report.pairs().getFirst().totalMsDelta()).isEqualTo(-30);
    }
    @Test void neverPairsDifferentRepetitionsAndShowsBothUnmatchedSides() {
        var a=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,10)),Map.of(0,grade(2,2)),"dataset");var b=run(List.of(row(0,"dev-01",2,Outcome.COMPLETED,10)),Map.of(0,grade(2,2)),"dataset");
        var report=new PairedRunComparator().compare(a,b,Mode.RAG);assertThat(report.unpaired()).isEqualTo(2);assertThat(report.qualityCompared()).isZero();
        assertThat(report.pairs()).extracting(PairedRunComparator.Pair::state).containsExactly(PairedRunComparator.PairState.ONLY_BASELINE,PairedRunComparator.PairState.ONLY_CANDIDATE);
    }
    @Test void missingManualGradesAreNotZeroAndNoEvidenceGetsNoModelLatencyDelta() {
        var a=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,20)),Map.of(),"dataset");var b=run(List.of(row(0,"dev-01",1,Outcome.NO_EVIDENCE,1)),Map.of(0,grade(0,2)),"dataset");
        var report=new PairedRunComparator().compare(a,b,Mode.RAG);var pair=report.pairs().getFirst();assertThat(report.unassessed()).isEqualTo(1);assertThat(pair.answerDelta()).isNull();assertThat(pair.totalMsDelta()).isNull();assertThat(pair.regression()).isFalse();
    }
    @Test void executionFailureIsARegressionEvenIfItsLatencyIsLower() {
        var a=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,50)),Map.of(0,grade(2,2)),"dataset");var b=run(List.of(row(0,"dev-01",1,Outcome.FAILED,1)),Map.of(),"dataset");
        var pair=new PairedRunComparator().compare(a,b,Mode.RAG).pairs().getFirst();assertThat(pair.state()).isEqualTo(PairedRunComparator.PairState.EXECUTION_REGRESSION);assertThat(pair.regression()).isTrue();assertThat(pair.totalMsDelta()).isNull();
    }
    @Test void betterAnswerWithWorseCitationsIsNotCalledUnambiguousImprovement() {
        var a=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,20)),Map.of(0,grade(1,2)),"dataset");var b=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,20)),Map.of(0,grade(2,0)),"dataset");
        var pair=new PairedRunComparator().compare(a,b,Mode.RAG).pairs().getFirst();assertThat(pair.answerDelta()).isEqualTo(1);assertThat(pair.citationDelta()).isEqualTo(-2);assertThat(pair.regression()).isTrue();assertThat(pair.improvement()).isFalse();
    }
    @Test void rejectsIncompatibleConditionsAndDuplicateKeys() {
        var a=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,20)),Map.of(),"one");var b=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,20)),Map.of(),"two");
        assertThatThrownBy(()->new PairedRunComparator().compare(a,b,Mode.RAG)).isInstanceOf(EvaluationProblem.class).extracting("code").isEqualTo("PAIR_CONDITIONS_MISMATCH");
        var duplicate=run(List.of(row(0,"dev-01",1,Outcome.COMPLETED,20),row(1,"dev-01",1,Outcome.COMPLETED,20)),Map.of(),"one");assertThatThrownBy(()->new PairedRunComparator().compare(a,duplicate,Mode.RAG)).isInstanceOf(IllegalArgumentException.class);
    }
}
