package dev.myfrei.repopilot.conversation;
import java.util.List;
import org.springframework.stereotype.Component;
@Component
public class HistorySearch {
    public List<Turn> find(List<Turn> snapshot,String conversationId,String query,int limit) {
        // TODO HW-4: literal Unicode-поиск по своим terminal turns, newest first, без изменения истории/памяти.
        throw new UnsupportedOperationException("TODO HW-4: history search");
    }
}
