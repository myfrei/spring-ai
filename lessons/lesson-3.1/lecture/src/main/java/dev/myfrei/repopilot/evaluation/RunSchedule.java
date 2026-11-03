package dev.myfrei.repopilot.evaluation;

import java.util.ArrayList;
import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.*;
public final class RunSchedule {
    private RunSchedule() { }
    public record Task(int ordinal, EvaluationCase entry, int repetition, Mode mode) { }
    public static List<Task> build(List<EvaluationCase> cases, Request request) {
        var tasks = new ArrayList<Task>();
        for (int repetition = 1; repetition <= request.repeats(); repetition++) {
            for (int i = 0; i < cases.size(); i++) {
                int offset = (i + repetition - 1) % request.modes().size();
                for (int j = 0; j < request.modes().size(); j++) {
                    tasks.add(new Task(tasks.size(), cases.get(i), repetition, request.modes().get((j + offset) % request.modes().size())));
                }
            }
        }
        return List.copyOf(tasks);
    }
}
