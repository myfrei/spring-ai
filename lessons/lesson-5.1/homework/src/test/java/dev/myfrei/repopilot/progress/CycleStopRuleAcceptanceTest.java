package dev.myfrei.repopilot.progress;

import java.util.*;
import dev.myfrei.repopilot.agent.ToolObservation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CycleStopRuleAcceptanceTest {
    private final CycleStopRule rule=new CycleStopRule();
    private ToolObservation value(int n){return new ToolObservation("getTask",String.format("%064x",n),"c".repeat(64),true);}
    @Test void noEvidenceOfRepetitionForEmptyOrSingleObservation(){assertThat(rule.afterTool(List.of())).isFalse();assertThat(rule.afterTool(List.of(value(1)))).isFalse();}
    @Test void twoConsecutiveIdenticalReadsAreARepeat(){var a=value(1);assertThat(rule.afterTool(List.of(a,a))).isTrue();assertThat(rule.afterTool(List.of(a,a,a))).isTrue();}
    @Test void periodTwoIsRecognizedAtTheTailEvenAfterAnUnrelatedPrefix(){var a=value(1);var b=value(2);assertThat(rule.afterTool(List.of(a,b,a,b))).isTrue();assertThat(rule.afterTool(List.of(value(9),a,b,a,b))).isTrue();}
    @Test void periodThreeIsRecognizedButPeriodFourAloneIsOutsideThisPolicy(){var a=value(1);var b=value(2);var c=value(3);var d=value(4);assertThat(rule.afterTool(List.of(a,b,c,a,b,c))).isTrue();assertThat(rule.afterTool(List.of(a,b,c,d,a,b,c,d))).isFalse();}
    @Test void toolNameArgumentsResultAndOutcomeAllParticipateInEquality(){
        var a=value(1);var changed=List.of(new ToolObservation("listTasks",a.argumentsHash(),a.resultHash(),true),value(2),new ToolObservation(a.name(),a.argumentsHash(),"d".repeat(64),true),new ToolObservation(a.name(),a.argumentsHash(),a.resultHash(),false));
        for(var b:changed)assertThat(rule.afterTool(List.of(a,b))).as(b.toString()).isFalse();
    }
    @Test void identicalErrorsAlsoMakeNoProgressButChangedResultsDo(){
        var error=new ToolObservation("getTask","a".repeat(64),"b".repeat(64),false);assertThat(rule.afterTool(List.of(error,error))).isTrue();
        assertThat(rule.afterTool(List.of(error,new ToolObservation(error.name(),error.argumentsHash(),"c".repeat(64),false)))).isFalse();
    }
    @Test void aRepeatedBlockInTheMiddleDoesNotStopNewProgressAtTheTail(){var a=value(1);var b=value(2);var c=value(3);assertThat(rule.afterTool(List.of(a,a,b,c))).isFalse();assertThat(rule.afterTool(List.of(a,b,a,c))).isFalse();assertThat(rule.afterTool(List.of(value(8),value(9),value(8),value(9),value(7),value(6),a,b,c,a,b,c))).isTrue();}
    @Test void callerListIsNotChangedAndProvidedInputBoundsApply(){var original=new ArrayList<>(List.of(value(1),value(2),value(1),value(2)));var copy=List.copyOf(original);assertThat(rule.afterTool(original)).isTrue();assertThat(original).containsExactlyElementsOf(copy);assertThatIllegalArgumentException().isThrownBy(()->rule.afterTool(null));assertThatIllegalArgumentException().isThrownBy(()->rule.afterTool(Collections.nCopies(13,value(1))));assertThatIllegalArgumentException().isThrownBy(()->rule.afterTool(Arrays.asList(value(1),null)));}
}
