package dev.myfrei.repopilot.evidence;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.myfrei.repopilot.evidence.EvidenceInput.*;
import static dev.myfrei.repopilot.evidence.EvidenceReport.Status.*;
import static org.assertj.core.api.Assertions.*;

class EvidencePolicyAcceptanceTest {
    private final EvidencePolicy policy=new EvidencePolicy();
    private final UUID run=UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String path="src/main/java/TaskController.java",hash="a".repeat(64),changed="b".repeat(64);
    private Claim claim(int a,int b){return new Claim(path,hash,a,b);}
    private ReadSpan read(int a,int b){return new ReadSpan(run,path,hash,a,b);}
    private EvidenceReport check(List<Claim> claims,List<ReadSpan> reads,Map<String,String> hashes){return policy.check(new EvidenceInput(run,claims,reads,hashes));}
    @Test void actualCurrentReadSupportsTheExactRequestedRange(){var result=check(List.of(claim(2,5)),List.of(read(1,8)),Map.of(path,hash));assertThat(result.runId()).isEqualTo(run);assertThat(result.allSupported()).isTrue();assertThat(result.checks()).hasSize(1);var item=result.checks().getFirst();assertThat(item.status()).isEqualTo(SUPPORTED);assertThat(item.path()).isEqualTo(path);assertThat(item.fullFileHash()).isEqualTo(hash);assertThat(item.startLine()).isEqualTo(2);assertThat(item.endLine()).isEqualTo(5);}
    @Test void overlappingAndAdjacentReadsCanBeMergedInAnyArrivalOrder(){var result=check(List.of(claim(2,8)),List.of(read(7,9),read(1,4),read(4,6),read(1,4)),Map.of(path,hash));assertThat(result.checks().getFirst().status()).isEqualTo(SUPPORTED);assertThat(result.allSupported()).isTrue();}
    @Test void aGapCannotBeHiddenByTheFirstAndLastRead(){var result=check(List.of(claim(3,7)),List.of(read(1,4),read(6,9)),Map.of(path,hash));assertThat(result.checks().getFirst().status()).isEqualTo(OUTSIDE_READ_RANGES);assertThat(result.allSupported()).isFalse();}
    @Test void anotherRunPathOrVersionNeverFillsTheGap(){var reads=List.of(read(1,2),new ReadSpan(UUID.randomUUID(),path,hash,3,9),new ReadSpan(run,"src/Other.java",hash,3,9),new ReadSpan(run,path,changed,3,9));assertThat(check(List.of(claim(1,9)),reads,Map.of(path,hash)).checks().getFirst().status()).isEqualTo(OUTSIDE_READ_RANGES);assertThat(check(List.of(claim(1,2)),List.of(new ReadSpan(UUID.randomUUID(),path,hash,1,9)),Map.of(path,changed)).checks().getFirst().status()).isEqualTo(NOT_READ);}
    @Test void oldVersionIsStaleAndUnreadNewVersionIsNotRead(){assertThat(check(List.of(claim(1,2)),List.of(read(1,1)),Map.of(path,changed)).checks().getFirst().status()).isEqualTo(STALE);var newer=new Claim(path,changed,1,2);assertThat(check(List.of(newer),List.of(read(1,9)),Map.of(path,changed)).checks().getFirst().status()).isEqualTo(NOT_READ);}
    @Test void missingCurrentHashMeansUnavailableOnlyAfterAnExactReadExists(){assertThat(check(List.of(claim(1,2)),List.of(read(1,1)),Map.of()).checks().getFirst().status()).isEqualTo(UNAVAILABLE);assertThat(check(List.of(claim(1,2)),List.of(),Map.of()).checks().getFirst().status()).isEqualTo(NOT_READ);}
    @Test void resultsKeepClaimOrderAndDuplicatesAndCollectionsAreImmutable(){var claims=new ArrayList<>(List.of(claim(5,6),claim(1,2),claim(1,2)));var reads=new ArrayList<>(List.of(read(1,3)));var hashes=new HashMap<>(Map.of(path,hash));var input=new EvidenceInput(run,claims,reads,hashes);claims.clear();reads.clear();hashes.clear();var result=policy.check(input);assertThat(result.checks()).extracting(EvidenceReport.Check::status).containsExactly(OUTSIDE_READ_RANGES,SUPPORTED,SUPPORTED);assertThat(result.allSupported()).isFalse();assertThatThrownBy(()->result.checks().clear()).isInstanceOf(UnsupportedOperationException.class);assertThat(input.reads()).hasSize(1);}
    @Test void inclusiveBoundariesAndProvidedBoundsAreRespected(){assertThat(check(List.of(claim(5,5),claim(5,6)),List.of(read(5,5)),Map.of(path,hash)).checks()).extracting(EvidenceReport.Check::status).containsExactly(SUPPORTED,OUTSIDE_READ_RANGES);assertThatIllegalArgumentException().isThrownBy(()->new Claim("../secret",hash,1,2));assertThatIllegalArgumentException().isThrownBy(()->new Claim(path,"bad",1,2));assertThatIllegalArgumentException().isThrownBy(()->claim(2,1));assertThatIllegalArgumentException().isThrownBy(()->new EvidenceInput(run,List.of(),List.of(),Map.of()));assertThatIllegalArgumentException().isThrownBy(()->new EvidenceInput(run,Collections.nCopies(13,claim(1,2)),List.of(),Map.of()));}
}
