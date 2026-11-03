package dev.myfrei.repopilot.mcphost;

import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.myfrei.repopilot.tasks.*;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class RemoteTaskValidator {
    private RemoteTaskValidator() { }
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static TaskView decode(McpSchema.CallToolResult result,String requestedId,RemoteReadMetadata metadata) {
        try {
            if (result==null) throw invalid(metadata);
            if (Boolean.TRUE.equals(result.isError())) throw new GatewayException(GatewayException.Kind.REMOTE_TOOL,"MCP_REMOTE_TOOL_ERROR","Удалённый инструмент вернул ошибку; результат не использован.",metadata);
            if (result.meta()!=null&&(result.meta().size()>16||bytes(JSON.writeValueAsString(result.meta()))>4096)) throw invalid(metadata);
            if (result.content()==null||result.content().size()!=1||!(result.content().getFirst() instanceof McpSchema.TextContent text)
                    ||text.text()==null||bytes(text.text())>8192) throw invalid(metadata);
            Object decoded=JSON.readValue(text.text(),Object.class);
            TaskView view=fromMap(decoded,requestedId,metadata);
            if (result.structuredContent()!=null) {
                if (bytes(JSON.writeValueAsString(result.structuredContent()))>8192) throw invalid(metadata);
                TaskView structured=fromMap(result.structuredContent(),requestedId,metadata);
                if (!view.equals(structured)) throw invalid(metadata);
            }
            return view;
        } catch (GatewayException error) { throw error; }
        catch (RuntimeException error) { throw invalid(metadata); }
    }
    private static TaskView fromMap(Object value,String requestedId,RemoteReadMetadata metadata) {
        if (!(value instanceof Map<?,?> map)||!map.keySet().equals(Set.of("id","title","status","summary"))
                ||!(map.get("id") instanceof String id)||!(map.get("title") instanceof String title)
                ||!(map.get("status") instanceof String status)||!(map.get("summary") instanceof String summary)) throw invalid(metadata);
        try { return validate(new TaskView(id,title,Task.Status.valueOf(status),summary),requestedId,metadata); }
        catch (RuntimeException error) { throw invalid(metadata); }
    }
    public static TaskView validate(TaskView view,String requestedId,RemoteReadMetadata metadata) {
        if (view==null||!requestedId.equals(view.id())||view.title()==null||view.title().isBlank()||view.title().length()>200
                ||view.status()==null||view.summary()==null||view.summary().isBlank()||view.summary().length()>1000) throw invalid(metadata);
        return view;
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static GatewayException invalid(RemoteReadMetadata metadata) { return new GatewayException(GatewayException.Kind.INVALID_RESPONSE,"MCP_INVALID_RESPONSE","Ответ MCP не соответствует публичному TaskView; тексты ответа не раскрыты.",metadata); }
}
