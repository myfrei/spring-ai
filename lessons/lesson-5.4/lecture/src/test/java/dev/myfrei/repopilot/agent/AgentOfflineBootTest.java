package dev.myfrei.repopilot.agent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"repopilot.agent.enabled=true","repopilot.rag.enabled=false","spring.ai.ollama.base-url=http://127.0.0.1:1","repopilot.rag.jdbc-url=jdbc:postgresql://127.0.0.1:1/offline"})
@ActiveProfiles("test")
class AgentOfflineBootTest {
 @Autowired AgentEngine engine;@Autowired AgentStore store;
 @Test void productionAgentBootsAndCreatesConversationsWithoutModelDatabaseOrMcp(){assertThat(engine).isNotNull();assertThat(store.createConversation().turns()).isEmpty();}
}
