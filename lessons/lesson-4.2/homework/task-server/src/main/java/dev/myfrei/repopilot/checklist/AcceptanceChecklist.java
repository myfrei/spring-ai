package dev.myfrei.repopilot.checklist;

import java.util.List;

public record AcceptanceChecklist(String taskId,List<Item> items,int eligibleCount,int omittedCount,int usedCodePoints,boolean complete,boolean budgetBlocked) {
    public AcceptanceChecklist {items=List.copyOf(items);}
    public record Item(String id,String text) { }
}
