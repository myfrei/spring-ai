package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.AgentRunControl;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Atomic shared admission accounting. Accepted reservations are never silently refunded. */
public final class ResearchBudget {
  private final ResearchLimits limits;
  private final BooleanSupplier externalCancelled;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private int models,
      tools,
      reservations,
      observed,
      inputTokens,
      outputTokens,
      children,
      childModels;
  private boolean usageComplete = true;

  public ResearchBudget(ResearchLimits limits) {
    this(limits, () -> false);
  }

  public ResearchBudget(ResearchLimits limits, BooleanSupplier externalCancelled) {
    this.limits = Objects.requireNonNull(limits);
    this.externalCancelled = Objects.requireNonNull(externalCancelled);
  }

  public void cancel() {
    cancelled.set(true);
  }

  public boolean cancelled() {
    return cancelled.get() || externalCancelled.getAsBoolean();
  }

  public synchronized void openChild() {
    if (cancelled()) throw new ResearchException("CANCELLED");
    if (children >= 1) throw new ResearchException("RESEARCH_CHILD_LIMIT");
    children++;
  }

  public AgentRunControl control(boolean child, Consumer<UUID> bind) {
    Objects.requireNonNull(bind);
    return new AgentRunControl() {
      @Override
      public String check(UUID id) {
        bind.accept(id);
        return cancelled() ? "CANCELLED" : null;
      }

      @Override
      public String beforeModel(UUID id, int estimatedInput, int outputReserve) {
        bind.accept(id);
        synchronized (ResearchBudget.this) {
          if (cancelled()) return "CANCELLED";
          if (child && childModels >= 3) return "RESEARCH_CHILD_MODEL_LIMIT";
          if (models >= limits.modelCalls()) return "RESEARCH_SHARED_MODEL_LIMIT";
          if (estimatedInput < 0 || outputReserve < 1)
            throw new IllegalArgumentException("Positive model reservation");
          long next = (long) reservations + estimatedInput + outputReserve;
          if (next > limits.estimatedTokenReservations()) return "RESEARCH_RESERVATION_LIMIT";
          models++;
          if (child) childModels++;
          reservations = (int) next;
          return null;
        }
      }

      @Override
      public String beforeTool(UUID id, String name) {
        bind.accept(id);
        synchronized (ResearchBudget.this) {
          if (cancelled()) return "CANCELLED";
          if (tools >= limits.toolRequests()) return "RESEARCH_SHARED_TOOL_LIMIT";
          tools++;
          return null;
        }
      }

      @Override
      public void afterModel(UUID id, Integer input, Integer output, boolean known) {
        bind.accept(id);
        synchronized (ResearchBudget.this) {
          observed++;
          if (!known || input == null || output == null || input < 0 || output < 0) {
            usageComplete = false;
            return;
          }
          try {
            inputTokens = Math.addExact(inputTokens, input);
            outputTokens = Math.addExact(outputTokens, output);
          } catch (ArithmeticException overflow) {
            usageComplete = false;
          }
        }
      }
    };
  }

  public synchronized ResearchBudgetSnapshot snapshot() {
    boolean complete = usageComplete && observed == models;
    return new ResearchBudgetSnapshot(
        limits,
        models,
        tools,
        reservations,
        observed,
        complete ? inputTokens : null,
        complete ? outputTokens : null,
        complete,
        children,
        childModels);
  }
}
