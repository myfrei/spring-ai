package dev.myfrei.repopilot.query;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import dev.myfrei.repopilot.rag.RagSearcher;
import static org.assertj.core.api.Assertions.*;

class QueryFeatureConfigurationTest {
    @Test void disablingRagAlsoDisablesDependentQueryBeansWithoutSql() {
        new ApplicationContextRunner().withUserConfiguration(QueryConfiguration.class,QueryController.class)
                .withPropertyValues("repopilot.query.enabled=true","repopilot.rag.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(QueryController.class)
                        .doesNotHaveBean(QueryRagPipeline.class).doesNotHaveBean(QueryModel.class).doesNotHaveBean(RagSearcher.class));
    }
}
