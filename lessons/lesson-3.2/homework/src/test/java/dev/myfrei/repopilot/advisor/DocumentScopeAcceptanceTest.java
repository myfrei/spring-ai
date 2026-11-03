package dev.myfrei.repopilot.advisor;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.ChatClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DocumentScopeAcceptanceTest {
    @Test void troubleshootingContainsOnlyRunbookAndKeepsManifestAndSourceData() {
        var original = AdvisorFixtures.retrieval(); var result = DocumentScope.select(DocumentScope.Profile.TROUBLESHOOTING, original);
        assertThat(result.manifest()).isEqualTo(original.manifest()); assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst()).isEqualTo(original.hits().getFirst()); assertThat(original.hits()).hasSize(2);
    }
    @Test void architectureExcludesRunbookAndRenumbersLabelsBeforePromptAssembly() {
        var found = AdvisorFixtures.retrieval(); var result = DocumentScope.select(DocumentScope.Profile.ARCHITECTURE, found);
        assertThat(result.hits()).hasSize(1); var source = result.hits().getFirst().source();
        assertThat(source.documentId()).isEqualTo("overview"); assertThat(source.label()).isEqualTo("S1");
        assertThat(source.chunkId()).isEqualTo(found.hits().get(1).source().chunkId()); assertThat(source.score()).isEqualTo(.8);
        assertThat(source.textHash()).isEqualTo(found.hits().get(1).source().textHash());
    }
    @Test void emptyAllowedSubsetNeverFallsBackToForbiddenSourcesOrCallsModel() {
        var found = AdvisorFixtures.retrieval(); var noRunbook = new RagSearchResult(found.manifest(), List.of(found.hits().get(1)));
        var selected = DocumentScope.select(DocumentScope.Profile.TROUBLESHOOTING, noRunbook); assertThat(selected.hits()).isEmpty();
        var searcher = mock(RagSearcher.class); when(searcher.search(any())).thenReturn(noRunbook);
        var model = new AdvisorFixtures.RecordingModel(); var service = new AdvisorCallService(ChatClient.builder(model), new RagPreparationService(searcher, AdvisorFixtures.planner()));
        assertThatThrownBy(() -> service.call(new RagRequest(AdvisorFixtures.QUESTION, 4, .35), result -> DocumentScope.select(DocumentScope.Profile.TROUBLESHOOTING, result)))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("NO_EVIDENCE");
        assertThat(model.calls.get()).isZero(); verify(searcher, times(1)).search(any());
    }
    @Test void scopedPromptAndReportContainOnlyPolicyApprovedDocuments() {
        var searcher = mock(RagSearcher.class); when(searcher.search(any())).thenReturn(AdvisorFixtures.retrieval());
        var model = new AdvisorFixtures.RecordingModel(); var service = new AdvisorCallService(ChatClient.builder(model), new RagPreparationService(searcher, AdvisorFixtures.planner()));
        var reply = service.call(new RagRequest(AdvisorFixtures.QUESTION, 4, .35), found -> DocumentScope.select(DocumentScope.Profile.ARCHITECTURE, found));
        assertThat(reply.rag().sources()).extracting(RagSource::documentId).containsExactly("overview");
        assertThat(model.received.getUserMessage().getText()).contains("Atlas", "[S1]").doesNotContain("Порт 8091", "RUNBOOK.md", "\n[S2]");
        verify(searcher, times(1)).search(any());
    }
    @Test void architecturePreservesRankAndEveryFieldOfRuntimeAndOverview() {
        var found = AdvisorFixtures.retrieval(); var original = found.hits().getFirst().source();
        var runtime = new RagHit(new RagSource("S9", original.chunkId(), "runtime", "adr/runtime.md", "Runtime", "Choice", 7, 11, original.textHash(), .99), "Runtime decision");
        var ranked = new RagSearchResult(found.manifest(), List.of(runtime, found.hits().getFirst(), found.hits().get(1)));
        var selected = DocumentScope.select(DocumentScope.Profile.ARCHITECTURE, ranked);
        assertThat(selected.hits()).extracting(hit -> hit.source().documentId()).containsExactly("runtime", "overview");
        assertThat(selected.hits()).extracting(hit -> hit.source().label()).containsExactly("S1", "S2");
        assertThat(selected.hits().getFirst()).isEqualTo(new RagHit(new RagSource("S1", original.chunkId(), "runtime", "adr/runtime.md", "Runtime", "Choice", 7, 11, original.textHash(), .99), "Runtime decision"));
    }
    @Test void selectionRunsBeforeBudgetSoExcludedFirstHitCannotExhaustIt() {
        var found = AdvisorFixtures.retrieval();
        var oversized = new RagHit(found.hits().getFirst().source(), "Forbidden detail ".repeat(10000));
        var ranked = new RagSearchResult(found.manifest(), List.of(oversized, found.hits().get(1)));
        assertThatThrownBy(() -> AdvisorFixtures.planner().plan(AdvisorFixtures.QUESTION, List.of(), ranked))
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("CONTEXT_TOO_LARGE");
        var searcher = mock(RagSearcher.class); when(searcher.search(any())).thenReturn(ranked);
        var service = new AdvisorCallService(ChatClient.builder(new AdvisorFixtures.RecordingModel()), new RagPreparationService(searcher, AdvisorFixtures.planner()));
        var reply = service.call(new RagRequest(AdvisorFixtures.QUESTION, 4, .35), result -> DocumentScope.select(DocumentScope.Profile.ARCHITECTURE, result));
        assertThat(reply.rag().sources()).extracting(RagSource::documentId).containsExactly("overview");
        verify(searcher, times(1)).search(any());
    }
    @Test void nullProfileIsRejectedAndPolicyResultIsImmutable() {
        assertThatThrownBy(() -> DocumentScope.select(null, AdvisorFixtures.retrieval())).isInstanceOf(IllegalArgumentException.class);
        var selected = DocumentScope.select(DocumentScope.Profile.ARCHITECTURE, AdvisorFixtures.retrieval());
        assertThatThrownBy(() -> selected.hits().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
