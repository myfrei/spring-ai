package dev.myfrei.repopilot.tasks;

import java.util.List;

public record ProjectInfo(String id,String name,String description,List<String> technologies,List<String> taskIds) {
    public ProjectInfo { technologies=List.copyOf(technologies); taskIds=List.copyOf(taskIds); }
}
