package dev.myfrei.repopilot.agent;

import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.Message;

public final class AgentStore {
  public static final int MAX_HISTORY_TURNS = 4, MAX_CONVERSATIONS = 64, MAX_RUNS = 256;

  private record Observer(Consumer<UUID> started, Consumer<AgentEvent> event) {}

  private final ThreadLocal<Observer> observer = new ThreadLocal<>();

  public interface Observation extends AutoCloseable {
    @Override
    void close();
  }

  /** A thread-scoped metadata observer on this store only; research children have another store. */
  public Observation observeRun(Consumer<UUID> started, Consumer<AgentEvent> event) {
    if (observer.get() != null) throw new IllegalStateException("One observer per dispatch thread");
    var owner = Thread.currentThread();
    observer.set(new Observer(Objects.requireNonNull(started), Objects.requireNonNull(event)));
    return () -> {
      if (Thread.currentThread() != owner) throw new IllegalStateException("Observer ownership");
      observer.remove();
    };
  }

  private void observeStarted(UUID id) {
    var listener = observer.get();
    if (listener != null)
      try {
        listener.started().accept(id);
      } catch (RuntimeException ignored) {
        /* Telemetry cannot corrupt run ownership. */
      }
  }

  private void observeEvent(AgentEvent event) {
    var listener = observer.get();
    if (listener != null)
      try {
        listener.event().accept(event);
      } catch (RuntimeException ignored) {
        /* UI delivery is not execution authorization. */
      }
  }

  private final Map<UUID, Conversation> conversations = new LinkedHashMap<>();
  private final Map<UUID, RunState> runs = new LinkedHashMap<>();

  public synchronized AgentConversation createConversation() {
    if (conversations.size() >= MAX_CONVERSATIONS)
      throw new AgentProblem("AGENT_CAPACITY", "Достигнут предел учебного in-memory хранилища.");
    var value = new Conversation(UUID.randomUUID());
    conversations.put(value.id, value);
    return value.view();
  }

  public synchronized AgentConversation conversation(UUID id) {
    return requiredConversation(id).view();
  }

  public synchronized AgentRun run(UUID id) {
    return requiredRun(id).view;
  }

  public synchronized List<AgentEvent> events(UUID id) {
    return List.copyOf(requiredRun(id).events);
  }

  public synchronized List<AgentMessage> messages(UUID id) {
    return List.copyOf(requiredRun(id).messages);
  }

  synchronized RunState begin(UUID conversationId, String question) {
    var conversation = requiredConversation(conversationId);
    if (conversation.active != null)
      throw new AgentProblem("AGENT_BUSY", "У разговора уже есть активный run.");
    if (runs.size() >= MAX_RUNS)
      throw new AgentProblem("AGENT_CAPACITY", "Достигнут предел учебного in-memory хранилища.");
    var run =
        new RunState(UUID.randomUUID(), conversationId, question, List.copyOf(conversation.turns));
    conversation.active = run.id;
    runs.put(run.id, run);
    observeStarted(run.id);
    return run;
  }

  synchronized void update(RunState run, AgentRun view) {
    run.view = view;
  }

  synchronized void event(RunState run, String type, Map<String, Object> values) {
    if (run.events.size() >= 40) throw new IllegalStateException("Run event bound exceeded");
    var event = new AgentEvent(run.events.size() + 1L, type, values);
    run.events.add(event);
    observeEvent(event);
  }

  synchronized void message(RunState run, Message message) {
    run.messages.add(AgentMessage.from(message));
  }

  synchronized void finish(RunState run, AgentRun view, List<Message> committed) {
    var conversation = requiredConversation(run.conversationId);
    if (!run.id.equals(conversation.active)) throw new IllegalStateException("Run ownership lost");
    if (view.status().equals("COMPLETED")) {
      conversation.turns.add(
          new Committed(
              new AgentTurn(run.id, run.question, view.answer()), List.copyOf(committed)));
      while (conversation.turns.size() > MAX_HISTORY_TURNS) conversation.turns.removeFirst();
    }
    run.view = view;
    conversation.active = null;
  }

  private Conversation requiredConversation(UUID id) {
    var value = conversations.get(id);
    if (value == null)
      throw new AgentProblem("AGENT_CONVERSATION_NOT_FOUND", "Разговор не найден.");
    return value;
  }

  private RunState requiredRun(UUID id) {
    var value = runs.get(id);
    if (value == null) throw new AgentProblem("AGENT_RUN_NOT_FOUND", "Run не найден.");
    return value;
  }

  private static final class Conversation {
    final UUID id;
    UUID active;
    final List<Committed> turns = new ArrayList<>();

    Conversation(UUID id) {
      this.id = id;
    }

    AgentConversation view() {
      return new AgentConversation(id, turns.stream().map(Committed::view).toList(), active);
    }
  }

  record Committed(AgentTurn view, List<Message> messages) {}

  static final class RunState {
    final UUID id, conversationId;
    final String question;
    final List<Committed> history;
    final List<AgentEvent> events = new ArrayList<>();
    final List<AgentMessage> messages = new ArrayList<>();
    AgentRun view;

    RunState(UUID id, UUID conversationId, String question, List<Committed> history) {
      this.id = id;
      this.conversationId = conversationId;
      this.question = question;
      this.history = history;
      this.view = new AgentRun(id, conversationId, "RUNNING", "", null, null);
    }
  }
}
