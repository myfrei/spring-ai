package dev.myfrei.repopilot.tools;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TaskToolsServiceTest {
    @Test void deterministicWorkflowUsesRealFunctionsWithoutModelOrIdentityAndIgnoresQuestionInstructions() {
        var model=new ToolsFixtures.RecordingModel();var identity=new ToolsFixtures.Identity();
        var reply=ToolsFixtures.service(model,identity,4096).workflow(ToolsFixtures.request());
        assertThat(reply.outcome()).isEqualTo("COMPLETED");assertThat(reply.task().id()).isEqualTo("TASK-101");
        assertThat(reply.report().modelCalls()).isZero();assertThat(reply.report().toolCalls()).isEqualTo(2);
        assertThat(model.calls).isZero();assertThat(identity.calls).isZero();
        assertThat(JsonMapper.builder().build().writeValueAsString(reply.report())).doesNotContain("PRIVATE-QUESTION","literal","acceptanceCriteria");
    }
    @Test void actualSdkAdvisorExecutesTaskCallbackThenFinalCallAndAccountsEachRoundOnce() {
        var model=new ToolsFixtures.RecordingModel();model.script.addAll(List.of(ToolsFixtures.taskCall(),ToolsFixtures.answer()));
        var identity=new ToolsFixtures.Identity();var reply=ToolsFixtures.service(model,identity,4096).model(ToolsFixtures.request());
        assertThat(reply.outcome()).isEqualTo("COMPLETED");assertThat(reply.task().id()).isEqualTo("TASK-101");
        assertThat(reply.report().modelCalls()).isEqualTo(2);assertThat(reply.report().toolCalls()).isOne();assertThat(reply.report().providerInputTokens()).isEqualTo(16);
        assertThat(reply.report().providerOutputTokens()).isEqualTo(6);assertThat(reply.report().usageComplete()).isTrue();assertThat(identity.calls).isEqualTo(2);
        assertThat(model.calls).isEqualTo(2);assertThat(model.prompts.get(1).getInstructions()).anyMatch(message->message instanceof ToolResponseMessage);
        var options=(OllamaChatOptions)model.prompts.getFirst().getOptions();
        assertThat(options.getNumPredict()).isEqualTo(384);assertThat(options.getNumCtx()).isEqualTo(4096);assertThat(options.getTemperature()).isEqualTo(0.0);
        String report=JsonMapper.builder().build().writeValueAsString(reply.report());assertThat(report).doesNotContain("PRIVATE-QUESTION","acceptanceCriteria","responseData","arguments");
    }
    @Test void directFinalAnswerWithoutActualRequestedTaskReadIsRejected() {
        var model=new ToolsFixtures.RecordingModel();model.script.add(ToolsFixtures.answer());
        assertThatThrownBy(()->ToolsFixtures.service(model,new ToolsFixtures.Identity(),4096).model(ToolsFixtures.request()))
                .isInstanceOf(ToolsProblem.class).satisfies(error->{var problem=(ToolsProblem)error;
                    assertThat(problem.code()).isEqualTo("TOOLS_REQUIRED_NOT_CALLED");assertThat(problem.report().modelCalls()).isOne();assertThat(problem.report().toolCalls()).isZero();});
    }
    @Test void endlessToolRequestsStopBeforeFifthModelCall() {
        var model=new ToolsFixtures.RecordingModel();model.repeat=true;
        assertThatThrownBy(()->ToolsFixtures.service(model,new ToolsFixtures.Identity(),4096).model(ToolsFixtures.request()))
                .isInstanceOf(ToolsProblem.class).satisfies(error->{var problem=(ToolsProblem)error;
                    assertThat(problem.code()).isEqualTo("TOOLS_MODEL_LIMIT");assertThat(problem.report().modelCalls()).isEqualTo(4);assertThat(problem.report().toolCalls()).isEqualTo(4);});
        assertThat(model.calls).isEqualTo(4);
    }
    @Test void nativeSdkManagerToolLimitCannotBecomeAnApparentlySuccessfulTaskAnswer() {
        var model=new ToolsFixtures.RecordingModel();var calls=new ArrayList<AssistantMessage.ToolCall>();
        calls.add(ToolsFixtures.tool("getTask","{\"taskId\":\"TASK-101\"}",1));for (int i=2;i<=5;i++) calls.add(ToolsFixtures.tool("getProjectInfo","{}",i));
        model.script.add(ToolsFixtures.response("","stop",calls));
        assertThatThrownBy(()->ToolsFixtures.service(model,new ToolsFixtures.Identity(),4096).model(ToolsFixtures.request()))
                .isInstanceOf(ToolsProblem.class).satisfies(error->{var problem=(ToolsProblem)error;
                    assertThat(problem.code()).isEqualTo("TOOLS_CALL_LIMIT");assertThat(problem.report().toolCalls()).isEqualTo(4);assertThat(problem.report().modelCalls()).isOne();});
        assertThat(model.calls).isOne();
    }
    @Test void invalidToolArgumentsAbortWithoutRetryAndDigestDriftStillTakesPrecedence() {
        for (boolean drift:List.of(false,true)) {
            var model=new ToolsFixtures.RecordingModel();model.script.add(ToolsFixtures.response("","stop",List.of(ToolsFixtures.tool("getTask","{\"taskId\":\"TASK-102\"}",1))));
            var identity=new ToolsFixtures.Identity();if (drift) identity.after="b".repeat(64);
            assertThatThrownBy(()->ToolsFixtures.service(model,identity,4096).model(ToolsFixtures.request()))
                    .isInstanceOf(ToolsProblem.class).satisfies(error->{var problem=(ToolsProblem)error;
                        assertThat(problem.code()).isEqualTo(drift?"TOOLS_MODEL_CHANGED":"TOOLS_WRONG_TASK");
                        assertThat(problem.report().modelCalls()).isOne();assertThat(problem.report().successfulToolCalls()).isZero();});
            assertThat(model.calls).isOne();assertThat(identity.calls).isEqualTo(2);
        }
    }
    @Test void unsupportedToolAndIncompleteFinalResponseAreExplicitFailures() {
        var unknown=new ToolsFixtures.RecordingModel();unknown.script.add(ToolsFixtures.response("","stop",List.of(ToolsFixtures.tool("readFile","{\"path\":\"/secret\"}",1))));
        assertThatThrownBy(()->ToolsFixtures.service(unknown,new ToolsFixtures.Identity(),4096).model(ToolsFixtures.request()))
                .isInstanceOf(ToolsProblem.class).extracting("code").isEqualTo("TOOLS_UNKNOWN_TOOL");
        var incomplete=new ToolsFixtures.RecordingModel();incomplete.script.addAll(List.of(ToolsFixtures.taskCall(),ToolsFixtures.response("partial","length",List.of())));
        assertThatThrownBy(()->ToolsFixtures.service(incomplete,new ToolsFixtures.Identity(),4096).model(ToolsFixtures.request()))
                .isInstanceOf(ToolsProblem.class).extracting("code").isEqualTo("TOOLS_INCOMPLETE");
    }
    @Test void budgetRejectionDoesNotCountAProviderAttemptAndUnknownTaskSkipsEverything() {
        var model=new ToolsFixtures.RecordingModel();var identity=new ToolsFixtures.Identity();
        assertThatThrownBy(()->ToolsFixtures.service(model,identity,730).model(ToolsFixtures.request()))
                .isInstanceOf(ToolsProblem.class).satisfies(error->{assertThat(((ToolsProblem)error).code()).isEqualTo("TOOLS_CONTEXT_TOO_LARGE");assertThat(((ToolsProblem)error).report().modelCalls()).isZero();});
        assertThat(model.calls).isZero();assertThat(identity.calls).isEqualTo(2);
        var service=ToolsFixtures.service(model,identity,4096);
        assertThatThrownBy(()->service.workflow(new ToolRequest("TASK-999","Q"))).isInstanceOf(ToolsProblem.class).extracting("code").isEqualTo("TOOLS_UNKNOWN_TASK");
    }
    @Test void schemaAndActualToolResponsesBothContributeToFullPromptBudget() {
        var run=new ToolRun("MODEL","TASK-101",3456,"");var tools=new TaskToolSet(new dev.myfrei.repopilot.tasks.TaskCatalog(),run);
        var plain=new org.springframework.ai.chat.prompt.Prompt(new UserMessage("Q"));
        int noSchema=new ToolPromptBudget(List.of()).estimate(plain);int withSchema=new ToolPromptBudget(tools.callbacks()).estimate(plain);
        var response=ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call","getTask","данные".repeat(100)))).build();
        int withResult=new ToolPromptBudget(tools.callbacks()).estimate(new org.springframework.ai.chat.prompt.Prompt(List.of(new UserMessage("Q"),response)));
        assertThat(withSchema).isGreaterThan(noSchema);assertThat(withResult).isGreaterThan(withSchema);
    }
}
