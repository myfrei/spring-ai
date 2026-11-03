package dev.myfrei.repopilot.mcphost;

import java.util.Objects;
import dev.myfrei.repopilot.tasks.TaskView;

public record ValidatedTaskRead(TaskView task,RemoteReadMetadata metadata) {
    public ValidatedTaskRead { Objects.requireNonNull(task);Objects.requireNonNull(metadata); }
}
