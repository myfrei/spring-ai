package dev.myfrei.repopilot.readiness;

import java.util.List;
import java.util.Set;

/** Server-owned fixture metadata, never proof that this process ran any checks. */
public record ReadinessInput(String taskId,String taskStatus,int currentRevision,List<Event> events) {
    public static final List<String> REQUIRED=List.of("BUILD","UNIT","INTEGRATION");
    public ReadinessInput {
        if(taskId==null||!taskId.matches("TASK-[0-9]{3}")||taskStatus==null||!Set.of("OPEN","IN_PROGRESS","DONE").contains(taskStatus)
                ||currentRevision<1||currentRevision>100||events==null||events.size()>100||events.stream().anyMatch(java.util.Objects::isNull))throw new IllegalArgumentException("Invalid readiness input");
        events=List.copyOf(events);
    }
    public record Event(String check,int revision,int attempt,String status) {
        public Event {
            if(check==null||!REQUIRED.contains(check)||revision<1||revision>100||attempt<1||attempt>100
                    ||status==null||!Set.of("PASS","FAIL","UNKNOWN").contains(status))throw new IllegalArgumentException("Invalid check event");
        }
    }
}
