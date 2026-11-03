package dev.myfrei.repopilot.checklist;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.mcp.TaskServerJson;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChecklistToolAdapterTest {
    static class CapturePolicy extends AcceptanceChecklistPolicy {
        int calls;ChecklistInput input;
        @Override public AcceptanceChecklist build(ChecklistInput input){calls++;this.input=input;return new AcceptanceChecklist(input.taskId(),List.of(new AcceptanceChecklist.Item("AC-001","ok")),1,0,2,true,false);}
    }
    @Test void strictSchemaAndReadOnlyAnnotationsAreProvided(){
        var tool=new ChecklistTool(new TaskCatalog(),new CapturePolicy()).specification().tool();
        assertThat(tool.name()).isEqualTo("getAcceptanceChecklist");
        assertThat(TaskServerJson.encode(tool)).contains("maxItems","maxCodePoints","additionalProperties","readOnlyHint");
    }
    @Test void invalidAndUnknownTaskNeverReachStudentPolicyOrEchoPrivateInput(){
        var policy=new CapturePolicy();var tool=new ChecklistTool(new TaskCatalog(),policy);
        var bad=new ArrayList<Map<String,Object>>();
        bad.add(Map.of());bad.add(Map.of("taskId","RAW-PRIVATE-MARKER"));bad.add(Map.of("taskId","TASK-101","maxItems",1.5));
        bad.add(Map.of("taskId","TASK-101","maxItems","2"));bad.add(Map.of("taskId","TASK-101","maxCodePoints",39));bad.add(Map.of("taskId","TASK-101","extra",true));
        var withNull=new HashMap<String,Object>();withNull.put("taskId","TASK-101");withNull.put("maxItems",null);bad.add(withNull);
        for(var args:bad){var result=tool.call(args);assertThat(result.isError()).isTrue();assertThat(TaskServerJson.encode(result)).contains("INVALID_ARGUMENTS").doesNotContain("RAW-PRIVATE-MARKER");}
        var missing=tool.call(Map.of("taskId","TASK-999"));assertThat(missing.isError()).isTrue();assertThat(TaskServerJson.encode(missing)).contains("TASK_NOT_FOUND");assertThat(policy.calls).isZero();
    }
    @Test void validArgumentsForwardInternalCriteriaAndLimitsWithoutProtocolDuplication(){
        var catalog=new TaskCatalog();var policy=new CapturePolicy();var result=new ChecklistTool(catalog,policy).call(Map.of("taskId","TASK-101","maxItems",2,"maxCodePoints",80));
        assertThat(result.isError()).isFalse();assertThat(policy.calls).isOne();assertThat(policy.input.criteria()).containsExactlyElementsOf(catalog.findTask("TASK-101").orElseThrow().acceptanceCriteria());
        assertThat(policy.input.maxItems()).isEqualTo(2);assertThat(policy.input.maxCodePoints()).isEqualTo(80);assertThat(TaskServerJson.encode(result)).contains("AC-001","complete","structuredContent");
    }
}
