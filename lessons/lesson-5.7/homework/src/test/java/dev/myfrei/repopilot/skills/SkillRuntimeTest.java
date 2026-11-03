package dev.myfrei.repopilot.skills;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

class SkillRuntimeTest {
  @TempDir Path root;

  @Test
  void explicitSkillBodiesAreBudgetedBeforeAnyModelOrIdentityRequest() throws Exception {
    Path skills = root.resolve(".agents/skills");
    Files.createDirectories(skills);
    for (String name : List.of("one", "two", "three")) {
      Path dir = skills.resolve(name);
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve("SKILL.md"),
          "---\nname: "
              + name
              + "\ndescription: Bounded procedure\n---\n"
              + "x".repeat(1200)
              + "\n");
    }
    var models = new AtomicInteger();
    var identities = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            models.incrementAndGet();
            throw new AssertionError("No model request expected");
          }
        };
    var store = new AgentStore();
    var base =
        new AgentEngine(
            model,
            () -> {
              identities.incrementAndGet();
              return "a".repeat(64);
            },
            List.of(),
            store,
            "fixture",
            700);
    var controlled = new ControlledService(base, new WorkspaceService(root), Clock.systemUTC());
    var runtime = new SkillRuntime(controlled, new SkillCatalog(skills));
    var conversation = store.createConversation();
    var result =
        runtime.run(
            conversation.id(), "Inspect the code", RunMode.ASK, List.of("one", "two", "three"));
    assertThat(result.run().stopReason()).isEqualTo("CONTEXT_LIMIT");
    assertThat(result.activations()).hasSize(3);
    assertThat(models).hasValue(0);
    assertThat(identities).hasValue(0);
    assertThat(store.conversation(conversation.id()).activeRunId()).isNull();
    assertThat(store.conversation(conversation.id()).turns()).isEmpty();
  }
}
