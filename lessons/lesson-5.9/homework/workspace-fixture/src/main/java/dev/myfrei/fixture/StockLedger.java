package dev.myfrei.fixture;

import java.util.Map;
import java.util.HashMap;

/** In-memory teaching domain. No I/O and no external services. */
public final class StockLedger {
    private final Map<String,Integer> stock;
    public StockLedger(Map<String,Integer> initial) {
        if(initial==null||initial.isEmpty()||initial.entrySet().stream().anyMatch(entry->
                entry.getKey()==null||entry.getKey().isBlank()||entry.getValue()==null||entry.getValue()<0))
            throw new IllegalArgumentException("Expected named SKUs and nonnegative stock");
        this.stock=new HashMap<>(initial);
    }
    public synchronized int available(String sku){return requireSku(sku);}
    public synchronized boolean reserve(String sku,int quantity) {
        if(quantity<1)throw new IllegalArgumentException("Quantity must be positive");
        int available=requireSku(sku);
        if(quantity>available)return false;
        this.stock.put(sku,available-quantity);
        return true;
    }
    public synchronized Map<String,Integer> snapshot(){return Map.copyOf(this.stock);}
    private int requireSku(String sku){
        Integer available=this.stock.get(sku);
        if(available==null)throw new IllegalArgumentException("Unknown SKU");
        return available;
    }
}
