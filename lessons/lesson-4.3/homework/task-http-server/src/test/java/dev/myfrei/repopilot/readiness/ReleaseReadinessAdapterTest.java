package dev.myfrei.repopilot.readiness;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import dev.myfrei.repopilot.mcp.TaskServerJson;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import static org.assertj.core.api.Assertions.*;

class ReleaseReadinessAdapterTest {
    static class Capture extends ReleaseReadinessPolicy {
        int calls;ReadinessInput input;
        @Override public ReleaseReadiness assess(ReadinessInput input){this.input=input;calls++;return new ReleaseReadiness(input.taskId(),input.currentRevision(),ReleaseReadiness.Verdict.UNKNOWN,List.of(),0,List.of("FIXTURE"),"COURSE_FIXTURE");}
    }
    private CallToolRequest request(Map<String,Object> args){return CallToolRequest.builder("getReleaseReadiness").arguments(args).build();}
    @Test void schemaExposesOnlyTaskIdNotClientControlledCheckResults(){
        var schema=new ReleaseReadinessTools(new TaskCatalog(),new Capture()).toolInputSchemas().get("getReleaseReadiness");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);assertThat(schema.get("required")).isEqualTo(List.of("taskId"));
        assertThat(TaskServerJson.encode(schema)).doesNotContain("events","PASS");
    }
    @Test void invalidInputsAndMissingTasksNeverReachPolicyOrEchoPayload(){
        var capture=new Capture();var adapter=new ReleaseReadinessTools(new TaskCatalog(),capture);
        for(var args:List.of(Map.<String,Object>of(),Map.<String,Object>of("taskId","RAW-PRIVATE-MARKER"),Map.<String,Object>of("taskId","TASK-105","events",List.of()))) {
            var result=adapter.readiness(request(args));assertThat(result.isError()).isTrue();assertThat(TaskServerJson.encode(result)).contains("INVALID_ARGUMENTS").doesNotContain("RAW-PRIVATE-MARKER");
        }
        assertThat(TaskServerJson.encode(adapter.readiness(request(Map.of("taskId","TASK-999"))))).contains("TASK_NOT_FOUND");assertThat(capture.calls).isZero();
    }
    @Test void serverBuildsImmutableFixtureFromKnownTaskBeforeCallingPolicy(){
        var capture=new Capture();var adapter=new ReleaseReadinessTools(new TaskCatalog(),capture);
        var result=adapter.readiness(request(Map.of("taskId","TASK-105")));assertThat(result.isError()).isFalse();assertThat(capture.calls).isOne();
        assertThat(capture.input.taskStatus()).isEqualTo("DONE");assertThat(capture.input.currentRevision()).isEqualTo(2);assertThat(capture.input.events()).hasSize(3);
        assertThat(capture.input.events()).extracting(ReadinessInput.Event::status).containsOnly("PASS");
        assertThat(TaskServerJson.encode(result)).contains("COURSE_FIXTURE");
    }
}
