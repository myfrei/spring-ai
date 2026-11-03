package dev.myfrei.fixture;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockLedgerTest {
    @Test void successfulReservationDecreasesOnlyChosenSku(){
        var ledger=new StockLedger(Map.of("JAVA25",3,"SPRINGAI",2));
        assertTrue(ledger.reserve("JAVA25",2));assertEquals(1,ledger.available("JAVA25"));assertEquals(2,ledger.available("SPRINGAI"));
    }
    @Test void insufficientStockDoesNotChangeInventory(){
        var ledger=new StockLedger(Map.of("JAVA25",3));
        assertFalse(ledger.reserve("JAVA25",4));assertEquals(Map.of("JAVA25",3),ledger.snapshot());
    }
    @Test void invalidQuantityOrUnknownSkuIsRejected(){
        var ledger=new StockLedger(Map.of("JAVA25",3));
        assertThrows(IllegalArgumentException.class,()->ledger.reserve("JAVA25",0));
        assertThrows(IllegalArgumentException.class,()->ledger.reserve("JAVA25",-1));
        assertThrows(IllegalArgumentException.class,()->ledger.reserve("UNKNOWN",1));
    }
    @Test void snapshotsAreIndependentAndImmutable(){
        var ledger=new StockLedger(Map.of("JAVA25",3));var before=ledger.snapshot();ledger.reserve("JAVA25",1);
        assertEquals(3,before.get("JAVA25"));assertThrows(UnsupportedOperationException.class,()->before.clear());
    }
}
