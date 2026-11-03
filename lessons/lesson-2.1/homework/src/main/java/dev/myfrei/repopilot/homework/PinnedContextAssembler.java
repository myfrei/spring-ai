package dev.myfrei.repopilot.homework;

import java.util.*;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

@Component
public class PinnedContextAssembler {
    private final ContextAssembler baseline;
    private final Utf8TokenEstimator estimator;
    public PinnedContextAssembler(ContextAssembler baseline, Utf8TokenEstimator estimator) { this.baseline = baseline; this.estimator = estimator; }
    public ContextPlan assemble(String question, ContextMode mode, List<Message> history, CorpusSnapshot corpus, List<Integer> pinnedPairs) {
        // TODO HW-2.1: реализовать новое поведение из README.
        throw new UnsupportedOperationException("TODO HW-2.1");
    }
    private static List<Message> messages(List<Message> mandatory, List<Message> history, SortedSet<Integer> pairs) {
        var result = new ArrayList<Message>(); result.add(mandatory.getFirst());
        for (int index : pairs) { result.add(history.get(index * 2)); result.add(history.get(index * 2 + 1)); }
        result.add(mandatory.getLast()); return List.copyOf(result);
    }
}
