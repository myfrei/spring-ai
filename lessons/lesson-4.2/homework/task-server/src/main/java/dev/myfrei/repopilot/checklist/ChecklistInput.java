package dev.myfrei.repopilot.checklist;

import java.util.List;

public record ChecklistInput(String taskId,List<String> criteria,int maxItems,int maxCodePoints) {
    public ChecklistInput {
        if(taskId==null||!taskId.matches("TASK-[0-9]{3}")||criteria==null||criteria.size()>20
                ||maxItems<1||maxItems>8||maxCodePoints<40||maxCodePoints>800)throw new IllegalArgumentException("Invalid checklist bounds");
        if(criteria.stream().anyMatch(text->text==null||text.length()>4000))throw new IllegalArgumentException("Invalid criterion");
        criteria=List.copyOf(criteria);
    }
}
