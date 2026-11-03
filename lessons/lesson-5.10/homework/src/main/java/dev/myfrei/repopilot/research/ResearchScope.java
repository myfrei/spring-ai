package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** One parent owns one child and one cooperative cancellation flag. */
final class ResearchScope {
  final ResearchBudget budget;
  final UUID conversationId;
  private final BiConsumer<UUID, ResearchScope> registration;
  private UUID parentRunId, childRunId;
  private AgentStore childStore;
  private ResearchChildReport child;

  ResearchScope(
      UUID conversationId, ResearchLimits limits, BiConsumer<UUID, ResearchScope> registration) {
    this(conversationId, limits, registration, () -> false);
  }

  ResearchScope(
      UUID conversationId,
      ResearchLimits limits,
      BiConsumer<UUID, ResearchScope> registration,
      BooleanSupplier externalCancelled) {
    this.conversationId = Objects.requireNonNull(conversationId);
    this.budget = new ResearchBudget(limits, externalCancelled);
    this.registration = registration;
  }

  synchronized void bindParent(UUID id) {
    if (parentRunId == null) {
      parentRunId = id;
      registration.accept(id, this);
    }
    if (!parentRunId.equals(id)) throw new IllegalStateException("Parent ownership");
  }

  synchronized UUID parentRunId() {
    return parentRunId;
  }

  synchronized void bindChild(UUID id, AgentStore isolated) {
    if (childRunId == null) {
      childRunId = id;
      childStore = isolated;
      child = new ResearchChildReport(id, "RUNNING", List.of(), List.of());
    }
    if (!childRunId.equals(id)) throw new IllegalStateException("Child ownership");
  }

  synchronized void finishChild(ResearchChildReport report) {
    if (!report.childRunId().equals(childRunId)) throw new IllegalStateException("Child ownership");
    child = report;
  }

  synchronized List<ResearchChildReport> children() {
    return child == null ? List.of() : List.of(child);
  }

  synchronized List<AgentMessage> childMessages(UUID id) {
    if (childStore == null || !id.equals(childRunId))
      throw new ResearchException("RESEARCH_CHILD_NOT_FOUND");
    return childStore.messages(id);
  }

  synchronized ResearchSnapshot snapshot(String status) {
    return new ResearchSnapshot(
        parentRunId, status, budget.cancelled(), budget.snapshot(), children());
  }
}
