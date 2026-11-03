package dev.myfrei.repopilot.tools;

import java.util.Set;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.model.tool.*;

/** Uses the real Spring AI loop; hooks add request-local limits and per-round accounting. */
public final class BoundedToolCallingAdvisor extends ToolCallingAdvisor {
    private final ToolRun run; private final ToolPromptBudget budget;
    public BoundedToolCallingAdvisor(ToolRun run,ToolPromptBudget budget) {
        super(ToolCallingManager.builder().maxTotalToolCalls(ToolRun.MAX_TOOL_CALLS).maxCallsPerTool(ToolRun.MAX_TOOL_CALLS)
                .onLimitExceeded(ToolCallLimitBehavior.THROW).resolutionFallbackEnabled(false)
                .toolExecutionExceptionProcessor(error->{throw error;}).build(),DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,DEFAULT_ORDER,true);
        this.run=run;this.budget=budget;
    }
    @Override protected ChatClientRequest doBeforeCall(ChatClientRequest request,CallAdvisorChain chain) {
        run.beginRound(budget.estimate(request.prompt())); return request;
    }
    @Override protected ChatClientResponse doAfterCall(ChatClientResponse response,CallAdvisorChain chain) {
        run.completeRound(response.chatResponse());
        if (response.chatResponse()!=null&&response.chatResponse().getResult()!=null) {
            for (var call:response.chatResponse().getResult().getOutput().getToolCalls()) {
                if (!Set.of("getProjectInfo","getTask").contains(call.name())||!"function".equals(call.type()))
                    throw run.problem("TOOLS_UNKNOWN_TOOL","Модель запросила инструмент вне разрешённого набора. Используйте /api/tools/workflow.");
            }
        }
        return response;
    }
}
