package dev.myfrei.repopilot.tools;

import java.util.*;
import dev.myfrei.repopilot.tasks.TaskCatalog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class ToolsFixtures {
    static final String DIGEST="a".repeat(64),QUESTION="PRIVATE-QUESTION: опиши задачу {literal}";
    static ToolRequest request() { return new ToolRequest("TASK-101",QUESTION); }
    static ChatResponse response(String text,String finish,List<AssistantMessage.ToolCall> tools) {
        var message=AssistantMessage.builder().content(text).toolCalls(tools).build();
        return new ChatResponse(List.of(new Generation(message,ChatGenerationMetadata.builder().finishReason(finish).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(8,3)).build());
    }
    static AssistantMessage.ToolCall tool(String name,String args,int id) { return new AssistantMessage.ToolCall("call-"+id,"function",name,args); }
    static ChatResponse taskCall() { return response("","stop",List.of(tool("getTask","{\"taskId\":\"TASK-101\"}",1))); }
    static ChatResponse answer() { return response("Сведения задачи получены [TASK-101].","stop",List.of()); }
    static class RecordingModel implements ChatModel {
        int calls;final List<Prompt> prompts=new ArrayList<>();final List<ChatResponse> script=new ArrayList<>();boolean repeat;RuntimeException failure;
        @Override public OllamaChatOptions getOptions() { return OllamaChatOptions.builder().model("fixture").temperature(.2).numCtx(4096).numPredict(256).build(); }
        @Override public ChatResponse call(Prompt prompt) {
            calls++;prompts.add(prompt);if (failure!=null) throw failure;
            if (repeat) return response("","stop",List.of(tool("getProjectInfo","{}",calls)));
            return script.get(Math.min(calls-1,script.size()-1));
        }
    }
    static class Identity implements ToolModelIdentity {
        int calls;String after=DIGEST;RuntimeException initialFailure,afterFailure;
        public String digest() { calls++;if (calls==1&&initialFailure!=null) throw initialFailure;if (calls>1&&afterFailure!=null) throw afterFailure;return calls==1?DIGEST:after; }
    }
    static TaskToolsService service(RecordingModel model,Identity identity,int window) {
        return new TaskToolsService(new TaskCatalog(),ChatClient.builder(model),identity,"fixture",window);
    }
}
