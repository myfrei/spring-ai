package dev.myfrei.repopilot.tools;

import java.util.*;
import dev.myfrei.repopilot.context.Utf8TokenEstimator;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

public final class ToolPromptBudget {
    private final JsonMapper json=JsonMapper.builder().build();
    private final String definitions;
    public ToolPromptBudget(List<ToolCallback> callbacks) {
        definitions=json.writeValueAsString(callbacks.stream().map(callback->{var d=callback.getToolDefinition();
            return Map.of("name",d.name(),"description",d.description(),"inputSchema",d.inputSchema());}).toList());
    }
    public int estimate(Prompt prompt) {
        var messages=new ArrayList<Message>();
        for (var message:prompt.getInstructions()) {
            var data=new LinkedHashMap<String,Object>(); data.put("role",message.getMessageType().name());
            data.put("text",message.getText()==null?"":message.getText());
            if (message instanceof AssistantMessage assistant&&!assistant.getToolCalls().isEmpty()) data.put("toolCalls",assistant.getToolCalls());
            if (message instanceof ToolResponseMessage response) data.put("toolResponses",response.getResponses());
            messages.add(new UserMessage(json.writeValueAsString(data)));
        }
        messages.add(new SystemMessage("Available tool definitions: "+definitions));
        return new Utf8TokenEstimator().estimate(messages);
    }
}
