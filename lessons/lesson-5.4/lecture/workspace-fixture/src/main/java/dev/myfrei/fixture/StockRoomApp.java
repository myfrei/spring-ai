package dev.myfrei.fixture;

import java.util.Map;

public final class StockRoomApp {
    private StockRoomApp() { }
    public static void main(String[] args){
        var ledger=new StockLedger(Map.of("JAVA25",3,"SPRINGAI",2));
        System.out.println("Reserved JAVA25: "+ledger.reserve("JAVA25",2));
        System.out.println("Remaining: "+ledger.snapshot());
    }
}
