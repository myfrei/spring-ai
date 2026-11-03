package dev.myfrei.repopilot.tools;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TaskToolSetTest {
    private ToolRun run() { return new ToolRun("MODEL","TASK-101",3456,"hash"); }
    @Test void callbackDefinitionsHaveOnlyTwoToolsAndExactSchemasWithoutHiddenCriteria() {
        var tools=new TaskToolSet(new TaskCatalog(),run());
        assertThat(tools.callbacks()).extracting(callback->callback.getToolDefinition().name()).containsExactly("getProjectInfo","getTask");
        String schema=tools.callbacks().get(1).getToolDefinition().inputSchema();
        Map<?,?> parsed=JsonMapper.builder().build().readValue(schema,Map.class);
        assertThat(parsed.get("additionalProperties")).isEqualTo(false);assertThat(parsed.get("required")).isEqualTo(List.of("taskId"));
        assertThat(schema).contains("TASK-101").doesNotContain("TASK-105","acceptanceCriteria");
        var another=new TaskToolSet(new TaskCatalog(),new ToolRun("MODEL","TASK-102",3456,"hash"));
        String second=another.callbacks().get(1).getToolDefinition().inputSchema();
        assertThat(second).contains("TASK-102").doesNotContain("TASK-101");
        assertThatThrownBy(()->tools.callbacks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void validatedFunctionCallbackReturnsOnlyPublicTaskViewAndAuditsOneSuccessfulActualCall() {
        var run=run();var tools=new TaskToolSet(new TaskCatalog(),run);
        String value=tools.invoke("getTask","{\"taskId\":\"TASK-101\"}");
        assertThat(value).contains("TASK-101","OPEN").doesNotContain("acceptanceCriteria");
        assertThat(run.report().toolCalls()).isOne();assertThat(run.report().successfulToolCalls()).isOne();assertThat(run.report().requiredTaskRead()).isTrue();
        assertThat(run.verifiedTask().id()).isEqualTo("TASK-101");
    }
    @ParameterizedTest @ValueSource(strings={"{}","[]","null","{\"taskId\":101}","{\"taskId\":\"TASK-101\",\"path\":\"/etc/passwd\"}",
            "{\"taskId\":\"TASK-101\",\"taskId\":\"TASK-101\"}","{\"taskId\":\"TASK-101\"} trailing","{\"taskId\":\"TASK-101\"}{}","{\"taskId\":\"TASK-999\"}","{\"taskId\":\"TASK-102\"}"})
    void rawArgumentsAreRejectedBeforeBusinessSuccessWithoutCoercionOrRepair(String arguments) {
        var run=run();var tools=new TaskToolSet(new TaskCatalog(),run);
        assertThatThrownBy(()->tools.invoke("getTask",arguments)).isInstanceOf(ToolsProblem.class);
        assertThat(run.report().toolCalls()).isOne();assertThat(run.report().successfulToolCalls()).isZero();assertThat(run.report().requiredTaskRead()).isFalse();
        assertThat(run.report().invocations().getFirst().outcome()).isEqualTo("REJECTED");assertThat(run.verifiedTask()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(run.report())).doesNotContain("/etc/passwd","trailing");
    }
    @Test void projectRejectsAllArgumentsAndUnknownToolNeverInvokesACallback() {
        var run=run();var tools=new TaskToolSet(new TaskCatalog(),run);
        assertThatThrownBy(()->tools.invoke("getProjectInfo","{\"command\":\"do something\"}")).isInstanceOf(ToolsProblem.class);
        assertThatThrownBy(()->tools.invoke("executeCommand","{}")).isInstanceOf(ToolsProblem.class).extracting("code").isEqualTo("TOOLS_UNKNOWN_TOOL");
        assertThat(run.report().toolCalls()).isOne();assertThat(run.report().successfulToolCalls()).isZero();
    }
    @Test void requestLocalAuditStaysBoundedAndCannotExecuteFifthCallback() {
        var run=run();var tools=new TaskToolSet(new TaskCatalog(),run);
        for (int i=0;i<4;i++) tools.invoke("getProjectInfo","{}");
        assertThatThrownBy(()->tools.invoke("getTask","{\"taskId\":\"TASK-101\"}")).isInstanceOf(ToolsProblem.class).extracting("code").isEqualTo("TOOLS_CALL_LIMIT");
        assertThat(run.report().invocations()).hasSize(4);assertThat(run.report().requiredTaskRead()).isFalse();
        assertThatThrownBy(()->run.report().invocations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
