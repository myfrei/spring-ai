package dev.myfrei.repopilot.batch;

import java.time.Instant;
import java.util.*;

/** Immutable metadata collected by the server, not accepted from a client. */
public record BatchInput(Instant now, List<Item> items) {
  public BatchInput {
    Objects.requireNonNull(now);
    if (items == null
        || items.isEmpty()
        || items.size() > 12
        || items.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Expected1–12 proposals");
    items = List.copyOf(items);
  }

  public record Item(
      UUID id,
      String path,
      String mode,
      String status,
      boolean runActive,
      String baseHash,
      String currentHash,
      Instant expiresAt) {
    public Item {
      Objects.requireNonNull(id);
      Objects.requireNonNull(path);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(status);
      Objects.requireNonNull(baseHash);
      Objects.requireNonNull(expiresAt);
    }
  }
}
