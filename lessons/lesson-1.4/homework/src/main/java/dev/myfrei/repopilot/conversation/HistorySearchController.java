package dev.myfrei.repopilot.conversation;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
public class HistorySearchController {
    private final SqliteHistory history;
    private final HistorySearch search;
    public HistorySearchController(SqliteHistory history,HistorySearch search){this.history=history;this.search=search;}
    @GetMapping("/api/conversations/{id}/search")
    public List<Turn> search(@PathVariable UUID id,@RequestParam String q,@RequestParam(defaultValue="10") int limit) {
        return this.search.find(this.history.turns(id.toString()),id.toString(),q,limit);
    }
}
