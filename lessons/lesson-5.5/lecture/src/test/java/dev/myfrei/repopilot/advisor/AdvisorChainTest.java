package dev.myfrei.repopilot.advisor;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.myfrei.repopilot.rag.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.Message;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdvisorChainTest {
    @Test void serviceAdvisorRetrievesAndPlansOnceForSynchronousCall() {
        var searcher = mock(RagSearcher.class); when(searcher.search(any())).thenReturn(AdvisorFixtures.retrieval());
        var model = new AdvisorFixtures.RecordingModel(); var preparation = new RagPreparationService(searcher, AdvisorFixtures.planner());
        var reply = new AdvisorCallService(ChatClient.builder(model), preparation).call(new RagRequest(AdvisorFixtures.QUESTION, 4, .35));
        verify(searcher, times(1)).search(any()); assertThat(model.calls.get()).isEqualTo(1); assertThat(reply.rag().sources()).hasSize(2);
        assertThat(model.received.getUserMessage().getText()).contains("Порт {literal}?", "{\"id\":\"TS-42\"}", "[S1]");
    }
    @Test void streamPreparationIsLazyAndNotRepeatedForDeltas() {
        var searcher = mock(RagSearcher.class); when(searcher.search(any())).thenReturn(AdvisorFixtures.retrieval());
        var model = new AdvisorFixtures.RecordingModel(); var preparation = new RagPreparationService(searcher, AdvisorFixtures.planner());
        var stream = ChatClient.builder(model).build().prompt().user(AdvisorFixtures.QUESTION)
                .advisors(new InputContractAdvisor(), new ServiceRagAdvisor(preparation, 4, .35)).stream().chatResponse();
        verifyNoInteractions(searcher); assertThat(model.streams.get()).isZero();
        var chunks = stream.collectList().block(); verify(searcher, times(1)).search(any()); assertThat(model.streams.get()).isEqualTo(1);
        assertThat(chunks).hasSize(4); assertThat((Object) chunks.getLast().getMetadata().get(SnapshotRagAdvisor.REPORT)).isInstanceOf(RagReport.class);
    }
    @Test void noEvidenceStopsBeforeModelInBothModes() {
        var searcher = mock(RagSearcher.class); when(searcher.search(any())).thenReturn(new RagSearchResult(AdvisorFixtures.retrieval().manifest(), List.of()));
        var model = new AdvisorFixtures.RecordingModel(); var preparation = new RagPreparationService(searcher, AdvisorFixtures.planner());
        var client = ChatClient.builder(model).build();
        assertThatThrownBy(() -> client.prompt().user(AdvisorFixtures.QUESTION).advisors(new ServiceRagAdvisor(preparation, 4, .35)).call().content()).isInstanceOf(RagProblem.class);
        assertThatThrownBy(() -> client.prompt().user(AdvisorFixtures.QUESTION).advisors(new ServiceRagAdvisor(preparation, 4, .35)).stream().content().collectList().block())
                .hasRootCauseInstanceOf(RagProblem.class);
        assertThat(model.calls.get()).isZero(); assertThat(model.streams.get()).isZero(); verify(searcher, times(2)).search(any());
    }
    @Test void advisorOrderIsStackNotRegistrationOrder() {
        var events = new ArrayList<String>(); var model = new AdvisorFixtures.RecordingModel();
        var client = ChatClient.builder(model).build();
        client.prompt().user("Q").advisors(new Probe("B", 20, events), new Probe("A", 10, events)).call().content();
        assertThat(events).containsExactly("A.before", "B.before", "B.after", "A.after");
        events.clear(); client.prompt().user("Q").advisors(new Probe("B", 20, events), new Probe("A", 10, events)).stream().content().collectList().block();
        assertThat(events).containsExactly("A.before", "B.before", "B.after", "A.after");
    }
    @Test void wrongOrderFailsBeforeModelAndSnapshotNeedsNoSearchService() {
        var model = new AdvisorFixtures.RecordingModel(); var plan = AdvisorFixtures.plan();
        assertThatThrownBy(() -> ChatClient.builder(model).build().prompt().messages(plan.baseMessages())
                .advisors(SnapshotAdvisorFactory.create(plan), new InputContractAdvisor(100)).call().content())
                .isInstanceOf(RagProblem.class).extracting("code").isEqualTo("ADVISOR_ORDER");
        assertThat(model.calls.get()).isZero();
        var response = ChatClient.builder(model).build().prompt().messages(plan.baseMessages()).advisors(SnapshotAdvisorFactory.create(plan)).call().chatResponse();
        assertThat(model.calls.get()).isEqualTo(1); assertThat((Object) response.getMetadata().get(SnapshotRagAdvisor.REPORT)).isEqualTo(plan.report());
        assertThat(model.received.getInstructions()).extracting(Message::getText).containsExactlyElementsOf(AdvisorFixtures.planner().projected(plan).stream().map(Message::getText).toList());
    }
    private record Probe(String name, int order, List<String> events) implements BaseAdvisor {
        @Override public int getOrder() { return order; }
        @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) { events.add(name + ".before"); return request; }
        @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { events.add(name + ".after"); return response; }
    }
}
