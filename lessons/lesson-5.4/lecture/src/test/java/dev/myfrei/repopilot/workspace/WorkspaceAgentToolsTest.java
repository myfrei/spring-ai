package dev.myfrei.repopilot.workspace;

import java.nio.file.*;
import java.util.*;
import dev.myfrei.repopilot.agent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class WorkspaceAgentToolsTest {
    @TempDir Path root;
    private AgentTool tool(WorkspaceService service,String name){return WorkspaceAgentTools.create(service).stream().filter(t->t.name().equals(name)).findFirst().orElseThrow();}
    private AgentToolContext context(){return new AgentToolContext(UUID.randomUUID(),UUID.randomUUID());}
    @Test void schemasAreClosedFlatAndExactlyFiveReadOnlyOperationsAreRegistered(){
        var tools=WorkspaceAgentTools.create(new WorkspaceService(root));assertThat(tools).extracting(AgentTool::name).containsExactly("listFiles","searchText","readFile","gitStatus","gitDiff");
        for(var tool:tools){var schema=(Map<?,?>)AgentJson.parse(tool.inputSchema());assertThat(schema.get("additionalProperties")).isEqualTo(false);assertThat(schema.get("type")).isEqualTo("object");assertThat(schema.get("properties")).isInstanceOf(Map.class);}
    }
    @Test void directCallsAlsoRejectCoercionsExtraKeysDuplicateJsonAndMissingContext()throws Exception{
        Files.writeString(root.resolve("README.md"),"line\n");var service=new WorkspaceService(root);var tool=tool(service,"readFile");
        for(String args:List.of("{\"path\":\"README.md\",\"startLine\":\"1\",\"endLine\":1}","{\"path\":\"README.md\",\"startLine\":1.0,\"endLine\":1}","{\"path\":\"README.md\",\"startLine\":null,\"endLine\":1}","{\"path\":\"README.md\",\"startLine\":1,\"endLine\":1,\"runId\":\"forged\"}","{\"path\":\"README.md\",\"path\":\"README.md\",\"startLine\":1,\"endLine\":1}","{} {}")){
            var result=tool.execute(args,context());assertThat(result.ok()).isFalse();assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
        }
        assertThat(tool.execute("{}",null).code()).isEqualTo("INVALID_CONTEXT");
    }
    @Test void runIdComesOnlyFromContextAndRejectedReadDoesNotRecordEvidence()throws Exception{
        Files.writeString(root.resolve("README.md"),"readable\n");var service=new WorkspaceService(root);var context=context();var tool=tool(service,"readFile");
        var success=tool.execute("{\"path\":\"README.md\",\"startLine\":1,\"endLine\":1}",context);assertThat(success.ok()).isTrue();assertThat(service.receipts(context.runId().toString())).hasSize(1);
        var other=context();var denied=tool.execute("{\"path\":\"/outside/RAW-PRIVATE\",\"startLine\":1,\"endLine\":1}",other);assertThat(denied.code()).isEqualTo("PATH_NOT_ALLOWED");assertThat(denied.content()).doesNotContain("RAW-PRIVATE");assertThatThrownBy(()->service.receipts(other.runId().toString())).isInstanceOf(WorkspaceException.class);
    }
    @Test void workspaceBeansRequireBothFlagsAndDisabledFeatureDoesNotResolveRoot(){
        new ApplicationContextRunner().withUserConfiguration(WorkspaceConfiguration.class).withPropertyValues("repopilot.workspace.enabled=true","repopilot.agent.enabled=false","repopilot.workspace.root=/not-an-available-root").run(context->assertThat(context).doesNotHaveBean(WorkspaceService.class));
        new ApplicationContextRunner().withUserConfiguration(WorkspaceConfiguration.class).withPropertyValues("repopilot.workspace.enabled=true","repopilot.agent.enabled=true","repopilot.workspace.root="+root).run(context->{assertThat(context).hasSingleBean(WorkspaceService.class);assertThat(context.getBeansOfType(AgentTool.class)).hasSize(5);});
    }
}
