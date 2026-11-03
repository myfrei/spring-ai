package dev.myfrei.repopilot.taskpage;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskPageAdapterTest {
    @Test void newCallbackIsExplicitAndSchemaHasBoundedLimitAndCursor(){
        var tool=new TaskPageTool(new TaskCatalog(),new TaskPagePolicy());
        var schema=JsonMapper.builder().build().readTree(tool.getToolDefinition().inputSchema());
        assertThat(tool.getToolDefinition().name()).isEqualTo("listTasks");
        assertThat(schema.path("properties").path("limit").path("maximum").asInt()).isEqualTo(5);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }
    @Test void invalidJsonTypesDuplicateKeysAndExtrasNeverReachStudentPolicy(){
        var policy=mock(TaskPagePolicy.class);var tool=new TaskPageTool(new TaskCatalog(),policy);
        for(String input:List.of("null","{}","{\"status\":\"ALL\",\"limit\":1.5}","{\"status\":\"ALL\",\"limit\":\"2\"}",
                "{\"status\":\"ALL\",\"limit\":2,\"limit\":3}","{\"status\":\"ALL\",\"limit\":2} {}",
                "{\"status\":\"ALL\",\"limit\":2,\"cursor\":null}","{\"status\":\"ALL\",\"limit\":2,\"extra\":true}"))
            assertThatIllegalArgumentException().isThrownBy(()->tool.call(input));
        verifyNoInteractions(policy);
    }
    @Test void newHttpAdapterUsesSanitizedErrorAndNeverReflectsPayload() throws Exception{
        var policy=mock(TaskPagePolicy.class);var api=MockMvcBuilders.standaloneSetup(new TaskPageController(new TaskCatalog(),policy)).setControllerAdvice(new TaskPageExceptionHandler()).build();
        var result=api.perform(post("/api/tools/tasks/page").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RAW-PRIVATE-MARKER\",\"limit\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_PAGE")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("RAW-PRIVATE-MARKER");verifyNoInteractions(policy);
    }
}
