package dev.myfrei.repopilot.http;

import java.util.*;
import dev.myfrei.repopilot.mcp.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.ai.mcp.annotation.provider.prompt.SyncMcpPromptProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AnnotationContractTest {
    private final TaskMcpHandlers handlers=new TaskMcpHandlers(new TaskCatalog());
    private final TaskHttpFeatures features=new TaskHttpFeatures(this.handlers);
    private List<SyncToolSpecification> refine(List<SyncToolSpecification> raw,ToolInputSchemas... providers) {
        var beans=new DefaultListableBeanFactory();for(int i=0;i<providers.length;i++)beans.registerSingleton("arbitrary-provider-"+i,providers[i]);
        Object result=new StrictToolSchemaPostProcessor(beans.getBeanProvider(ToolInputSchemas.class)).postProcessAfterInitialization(raw,"arbitrary-list-bean");
        return ((List<?>)result).stream().map(SyncToolSpecification.class::cast).toList();
    }
    @Test void rawRequestAnnotationSchemaIsExplicitlyRefinedWithoutChangingCallbacks() {
        var raw=new SyncMcpToolProvider(List.of(this.features)).getToolSpecifications();
        for(var spec:raw)assertThat((Map<?,?>)spec.tool().inputSchema().get("properties")).isEmpty();
        var refined=refine(raw,this.features);assertThat(refined).hasSize(2);
        for(int i=0;i<refined.size();i++) {
            var spec=refined.get(i);assertThat(spec.tool().inputSchema()).isEqualTo(this.features.toolInputSchemas().get(spec.tool().name()));
            assertThat(spec.callHandler()).isSameAs(raw.get(i).callHandler());assertThat(spec.tool().annotations().readOnlyHint()).isTrue();assertThat(spec.tool().annotations().destructiveHint()).isFalse();
        }
    }
    @Test void annotatedRawArgumentsKeepStrictTypesAndSuccessResultMatchesStdioHandlers() {
        var specs=refine(new SyncMcpToolProvider(List.of(this.features)).getToolSpecifications(),this.features);
        var list=specs.stream().filter(spec->spec.tool().name().equals("listTasks")).findFirst().orElseThrow();
        for(Object value:List.of("2",2.5,2.0,0,6))assertThat(list.callHandler().apply(null,McpSchema.CallToolRequest.builder("listTasks").arguments(Map.of("limit",value)).build()).isError()).isTrue();
        var get=specs.stream().filter(spec->spec.tool().name().equals("getTask")).findFirst().orElseThrow();
        var args=Map.<String,Object>of("taskId","TASK-101");var actual=get.callHandler().apply(null,McpSchema.CallToolRequest.builder("getTask").arguments(args).build());
        var mapper=JsonMapper.builder().build();assertThat(mapper.readTree(TaskServerJson.encode(actual))).isEqualTo(mapper.readTree(TaskServerJson.encode(this.handlers.getTask(args))));
    }
    @Test void promptArgumentMetadataAndRawValidationAreBothPresent() {
        var prompt=new SyncMcpPromptProvider(List.of(this.features)).getPromptSpecifications().getFirst();
        assertThat(prompt.prompt().arguments()).hasSize(1);assertThat(prompt.prompt().arguments().getFirst().name()).isEqualTo("taskId");assertThat(prompt.prompt().arguments().getFirst().required()).isTrue();
        assertThatThrownBy(()->prompt.promptHandler().apply(null,new McpSchema.GetPromptRequest("analyze_task",Map.of("taskId",101),null))).isInstanceOf(io.modelcontextprotocol.spec.McpError.class);
    }
    @Test void extraAnnotationBeanContributesOnlyItsOwnSchemaAndDuplicatesFail() {
        var extra=new ExtraFeature();var raw=new SyncMcpToolProvider(List.of(this.features,extra)).getToolSpecifications();var refined=refine(raw,this.features,extra);
        assertThat(refined).extracting(spec->spec.tool().name()).containsExactlyInAnyOrder("listTasks","getTask","extraProbe");
        assertThat(refined.stream().filter(spec->spec.tool().name().equals("extraProbe")).findFirst().orElseThrow().tool().inputSchema()).isEqualTo(extra.toolInputSchemas().get("extraProbe"));
        assertThatThrownBy(()->refine(raw,this.features,this.features)).isInstanceOf(IllegalStateException.class).hasMessage("Duplicate tool schema contribution");
    }
    static class ExtraFeature implements ToolInputSchemas {
        @McpTool(name="extraProbe")public McpSchema.CallToolResult probe(McpSchema.CallToolRequest request){return TaskServerJson.result(Map.of("probe",true));}
        @Override public Map<String,Map<String,Object>> toolInputSchemas(){return Map.of("extraProbe",Map.of("type","object","additionalProperties",false));}
    }
}
