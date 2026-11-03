package dev.myfrei.repopilot.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ToolsConfigurationTest {
    @Test void explicitToolsFlagControlsFeatureWithoutDependingOnRagFlags() {
        new ApplicationContextRunner().withUserConfiguration(ToolsConfiguration.class,ToolsController.class)
                .withPropertyValues("repopilot.tools.enabled=false","repopilot.rag.enabled=true")
                .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(ToolsController.class).doesNotHaveBean(TaskToolsService.class));
    }
}
