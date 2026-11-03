package dev.myfrei.repopilot.rerank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RerankConfigurationAndRequestTest {
    @ParameterizedTest @ValueSource(strings={"repopilot.rerank.enabled","repopilot.query.enabled","repopilot.rag.enabled"})
    void anyDisabledDependencyKeepsNewFeatureOffWithoutSqlOrModel(String disabled) {
        new ApplicationContextRunner().withUserConfiguration(RerankConfiguration.class,RerankController.class)
                .withPropertyValues("repopilot.rerank.enabled=true","repopilot.query.enabled=true","repopilot.rag.enabled=true",disabled+"=false")
                .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(RerankController.class).doesNotHaveBean(RerankModel.class));
    }
    @ParameterizedTest @ValueSource(strings={"1.9","1.0","\"2\"","true","0","5"})
    void newKeepFieldRejectsFractionStringBooleanAndOutOfRangeInsteadOfCoercing(String keep) {
        String request="{\"query\":{\"question\":\"Q\"},\"keep\":"+keep+"}";
        assertThatThrownBy(()->JsonMapper.builder().build().readValue(request,RerankRequest.class)).isInstanceOf(RuntimeException.class);
    }
    @Test void nullDefaultsAreExplicitAndMissingQueryIsInvalid() {
        var request=JsonMapper.builder().build().readValue("{\"query\":{\"question\":\"Q\"},\"keep\":null}",RerankRequest.class);
        assertThat(request.keep()).isEqualTo(4); assertThat(request.strategy()).isEqualTo(RerankRequest.Strategy.NONE);
        assertThatThrownBy(()->new RerankRequest(null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
