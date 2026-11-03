package dev.myfrei.repopilot.workspace;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkspaceReadLedgerTest {
    @Test void duplicateReceiptsDoNotConsumeCapacityAndRunsNeverMix(){
        var ledger=new WorkspaceReadLedger();String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();var receipt=new ReadReceipt(a,"README.md","a".repeat(64),1,2);
        for(int i=0;i<300;i++)ledger.record(receipt);ledger.record(new ReadReceipt(b,"README.md","b".repeat(64),8,9));assertThat(ledger.receipts(a)).containsExactly(receipt);assertThat(ledger.receipts(b)).hasSize(1);
    }
    @Test void oldRunsBecomeExplicitlyUnavailableAndRecentRunsArePreserved(){
        var ledger=new WorkspaceReadLedger();String oldest=UUID.randomUUID().toString();ledger.record(new ReadReceipt(oldest,"README.md","a".repeat(64),1,1));
        for(int i=0;i<128;i++)ledger.record(new ReadReceipt(UUID.randomUUID().toString(),"README.md","b".repeat(64),1,1));
        assertThatThrownBy(()->ledger.receipts(oldest)).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("RECEIPTS_UNAVAILABLE"));
        assertThatThrownBy(()->ledger.record(new ReadReceipt(oldest,"README.md","a".repeat(64),3,4))).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("RECEIPTS_UNAVAILABLE"));
        assertThatThrownBy(()->ledger.receipts(oldest)).isInstanceOf(WorkspaceException.class);
        String latest=UUID.randomUUID().toString();
        for(int i=0;i<255;i++){latest=UUID.randomUUID().toString();ledger.record(new ReadReceipt(latest,"README.md","b".repeat(64),1,1));}
        assertThatThrownBy(()->ledger.record(new ReadReceipt(UUID.randomUUID().toString(),"README.md","c".repeat(64),1,1))).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("RECEIPTS_UNAVAILABLE"));
        assertThat(ledger.receipts(latest)).hasSize(1);
        assertThatThrownBy(()->ledger.record(new ReadReceipt(oldest,"README.md","a".repeat(64),5,6))).isInstanceOf(WorkspaceException.class);
    }
    @Test void perRunCapacityRejectsNewEvidenceWithoutDestroyingOldReceipts(){
        var ledger=new WorkspaceReadLedger();String run=UUID.randomUUID().toString();for(int i=1;i<=256;i++)ledger.record(new ReadReceipt(run,"README.md","a".repeat(64),i,i));
        assertThatThrownBy(()->ledger.record(new ReadReceipt(run,"README.md","a".repeat(64),257,257))).isInstanceOfSatisfying(WorkspaceException.class,e->assertThat(e.code()).isEqualTo("RECEIPT_LIMIT"));assertThat(ledger.receipts(run)).hasSize(256);
    }
    @Test void concurrentRecordingKeepsAnImmutableConsistentSnapshot()throws Exception {
        var ledger=new WorkspaceReadLedger();String run=UUID.randomUUID().toString();try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var work=new ArrayList<Future<?>>();for(int i=1;i<=100;i++){int line=i;work.add(executor.submit(()->ledger.record(new ReadReceipt(run,"README.md","a".repeat(64),line,line))));}for(var task:work)task.get();
        }
        assertThat(ledger.receipts(run)).hasSize(100);var snapshot=ledger.receipts(run);assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }
}
