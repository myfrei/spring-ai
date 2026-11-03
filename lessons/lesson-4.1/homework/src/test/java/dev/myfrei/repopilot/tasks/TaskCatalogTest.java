package dev.myfrei.repopilot.tasks;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TaskCatalogTest {
    @Test void deterministicReadOnlyCatalogHasStableIdsAndPublicViewsExcludeInternalCriteria() {
        var catalog=new TaskCatalog();
        assertThat(catalog.projectInfo().taskIds()).containsExactly("TASK-101","TASK-102","TASK-103","TASK-104","TASK-105");
        assertThat(catalog.listTaskViews()).extracting(TaskView::id).containsExactlyElementsOf(catalog.projectInfo().taskIds());
        var internal=catalog.findTask("TASK-101").orElseThrow();assertThat(internal.acceptanceCriteria()).isNotEmpty();
        String json=JsonMapper.builder().build().writeValueAsString(catalog.getTask("TASK-101"));
        assertThat(json).contains("TASK-101","OPEN").doesNotContain("acceptanceCriteria",internal.acceptanceCriteria().getFirst());
        assertThatThrownBy(()->catalog.listTaskViews().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->internal.acceptanceCriteria().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->catalog.projectInfo().taskIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void unknownOrMaliciousIdsNeverResolveToAnotherTask() {
        var catalog=new TaskCatalog();
        for (String id:java.util.List.of("TASK-999","task-101","../TASK-101","TASK-101; rm -rf /")) {
            assertThat(catalog.findTask(id)).isEmpty();assertThatThrownBy(()->catalog.getTask(id)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(catalog.findTask(null)).isEmpty();
    }
}
