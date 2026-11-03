package dev.myfrei.repopilot.taskpage;

import java.util.List;

public record TaskPage(List<Row> tasks, int total, int returned, String nextCursor, String snapshotVersion) {
    public TaskPage { tasks=List.copyOf(tasks); }
    public record Row(String id,String title,String status) {
        public Row {
            if(id==null||!id.matches("TASK-[0-9]{3}")||title==null||title.isBlank()||title.length()>200
                    ||status==null||!List.of("OPEN","IN_PROGRESS","DONE").contains(status)) throw new IllegalArgumentException("Invalid task row");
        }
    }
}
