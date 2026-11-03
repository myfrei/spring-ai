package dev.myfrei.repopilot.tasks;

import java.util.List;

/** Internal immutable domain record. Expose TaskView at public tool boundaries. */
public record Task(String id,String title,Status status,String summary,List<String> acceptanceCriteria) {
    public enum Status { OPEN, IN_PROGRESS, DONE }
    public Task {
        if (id==null||!id.matches("TASK-[0-9]{3}")||title==null||title.isBlank()||status==null||summary==null||summary.isBlank())
            throw new IllegalArgumentException("Valid task fields required");
        acceptanceCriteria=List.copyOf(acceptanceCriteria);
        if (acceptanceCriteria.isEmpty()||acceptanceCriteria.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Acceptance criteria required");
    }
    public TaskView toView() { return new TaskView(id,title,status,summary); }
}
