package dev.myfrei.repopilot.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TaskServerWireIT {
    @Test void stdoutIsOnlyUtf8JsonRpcLinesAndClosingStdinExitsZero() throws Exception {
        try(var wire=new Wire()) {
            var initialized=wire.initialize("2025-11-25");assertThat(initialized.get("result").get("protocolVersion").asText()).isEqualTo("2025-11-25");
            var list=wire.request("tools/list",Map.of());assertThat(list.get("result").get("tools").size()).isEqualTo(2);
            var task=wire.request("tools/call",Map.of("name","getTask","arguments",Map.of("taskId","TASK-101")));
            assertThat(task.get("result").get("isError").asBoolean()).isFalse();assertThat(task.get("result").get("structuredContent").get("title").asText()).contains("порт");
            var prompt=wire.request("prompts/get",Map.of("name","analyze_task","arguments",Map.of("taskId","TASK-101")));
            assertThat(prompt.get("result").get("messages").get(0).get("content").get("text").asText()).contains("\n");
            assertThat(wire.lines).allSatisfy(line->{assertThat(line).startsWith("{").endsWith("}");assertThat(JsonMapper.builder().build().readTree(line).get("jsonrpc").asText()).isEqualTo("2.0");});
            wire.finish();assertThat(wire.stderr()).contains("taskshelf-mcp-server ready").doesNotContain("jsonrpc","Согласовать HTTP-порт");
        }
    }

    @Test void rawJsonRpcErrorsAndToolExecutionErrorsUseDifferentEnvelopes() throws Exception {
        try(var wire=new Wire()) {
            wire.initialize("2025-11-25");
            var unknownMethod=wire.request("missing/method",Map.of());assertThat(unknownMethod.get("error").get("code").asInt()).isEqualTo(-32601);assertThat(unknownMethod.has("result")).isFalse();
            var unknownTool=wire.request("tools/call",Map.of("name","missingTool","arguments",Map.of()));assertThat(unknownTool.get("error").get("code").asInt()).isEqualTo(-32602);
            var invalid=wire.request("tools/call",Map.of("name","listTasks","arguments",Map.of("limit","2")));
            assertThat(invalid.has("error")).isFalse();assertThat(invalid.get("result").get("isError").asBoolean()).isTrue();assertThat(invalid.get("result").get("structuredContent").get("code").asText()).isEqualTo("INVALID_ARGUMENTS");
            assertThat(wire.request("tools/list",Map.of()).get("result").get("tools").size()).isEqualTo(2);wire.finish();
        }
    }

    @Test void sdkNegotiatesOlderSupportedVersionWithoutPretendingItIsLatest() throws Exception {
        try(var wire=new Wire()) {assertThat(wire.initialize("2025-06-18").get("result").get("protocolVersion").asText()).isEqualTo("2025-06-18");wire.finish();}
    }

    @Test void eofWithoutInitializationAlsoStopsProcessAndLeavesStdoutEmpty() throws Exception {
        try(var wire=new Wire()) {wire.finish();assertThat(wire.lines).isEmpty();}
    }

    private static final class Wire implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BufferedReader output;
        final ExecutorService readers=Executors.newVirtualThreadPerTaskExecutor();
        final Future<String> errors;
        final List<String> lines=new ArrayList<>();
        int sequence;boolean finished;
        Wire() throws IOException {
            var jar=Path.of(System.getProperty("taskshelf.server.jar")).toAbsolutePath().toString();
            this.process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar).start();
            this.input=new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(),StandardCharsets.UTF_8));
            this.output=new BufferedReader(new InputStreamReader(this.process.getInputStream(),StandardCharsets.UTF_8));
            this.errors=this.readers.submit(()->new String(this.process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
        }
        JsonNode initialize(String version) throws Exception {
            var result=request("initialize",Map.of("protocolVersion",version,"capabilities",Map.of(),"clientInfo",Map.of("name","course-wire-test","version","1.0")));
            send(Map.of("jsonrpc","2.0","method","notifications/initialized"));return result;
        }
        JsonNode request(String method,Map<String,Object> params) throws Exception {
            int id=++this.sequence;send(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params));
            String line=this.readers.submit(this.output::readLine).get(5,TimeUnit.SECONDS);assertThat(line).isNotNull();this.lines.add(line);
            var tree=JsonMapper.builder().build().readTree(line);assertThat(tree.get("id").asInt()).isEqualTo(id);return tree;
        }
        void send(Map<String,Object> value) throws IOException {this.input.write(TaskServerJson.encode(value));this.input.write('\n');this.input.flush();}
        void finish() throws Exception {
            this.input.close();assertThat(this.process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(this.process.exitValue()).isZero();
            assertThat(this.output.readLine()).isNull();this.finished=true;
        }
        String stderr() throws Exception {return this.errors.get(5,TimeUnit.SECONDS);}
        @Override public void close() throws Exception {
            if(this.process.isAlive()){this.process.destroy();if(!this.process.waitFor(3,TimeUnit.SECONDS))this.process.destroyForcibly();}
            this.readers.shutdownNow();this.readers.awaitTermination(2,TimeUnit.SECONDS);
        }
    }
}
