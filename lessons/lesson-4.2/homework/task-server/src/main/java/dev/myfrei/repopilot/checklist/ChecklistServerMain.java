package dev.myfrei.repopilot.checklist;

import java.util.List;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.mcp.TaskServerMain;

/** Separate entrypoint keeps the original two-tool server usable before the new policy is implemented. */
public final class ChecklistServerMain {
    private ChecklistServerMain() { }
    public static void main(String[] args){
        if(args.length!=0)throw new IllegalArgumentException("This server accepts no command-line arguments");
        var catalog=new TaskCatalog();
        TaskServerMain.start(catalog,List.of(new ChecklistTool(catalog,new AcceptanceChecklistPolicy()).specification()));
    }
}
