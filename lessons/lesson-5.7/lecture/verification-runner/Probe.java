package dev.course.verifier;

import dev.myfrei.fixture.StockLedger;
import java.util.Map;

/**
 * Immutable observation bridge. Assertions and final exit belong to VerificationMain, not target.
 */
public final class Probe {
  public static void main(String[] ignored) {
    var partial = new StockLedger(Map.of("JAVA25", 3, "OTHER", 2));
    boolean reserved = partial.reserve("JAVA25", 2);
    int left = partial.available("JAVA25"), other = partial.available("OTHER");
    var insufficient = new StockLedger(Map.of("JAVA25", 3));
    boolean shortage = insufficient.reserve("JAVA25", 4);
    int afterShortage = insufficient.available("JAVA25");
    var exact = new StockLedger(Map.of("JAVA25", 3));
    boolean all = exact.reserve("JAVA25", 3);
    int afterAll = exact.available("JAVA25");
    boolean invalid = reject(() -> new StockLedger(Map.of("JAVA25", 3)).reserve("JAVA25", 0));
    boolean unknown = reject(() -> new StockLedger(Map.of("JAVA25", 3)).reserve("UNKNOWN", 1));
    var snapshots = new StockLedger(Map.of("JAVA25", 3));
    var before = snapshots.snapshot();
    snapshots.reserve("JAVA25", 1);
    boolean independent = before.get("JAVA25") == 3;
    boolean immutable = false;
    try {
      before.put("JAVA25", 99);
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    System.out.println(
        "OBS_V1|"
            + reserved
            + "|"
            + left
            + "|"
            + other
            + "|"
            + shortage
            + "|"
            + afterShortage
            + "|"
            + all
            + "|"
            + afterAll
            + "|"
            + invalid
            + "|"
            + unknown
            + "|"
            + independent
            + "|"
            + immutable);
  }

  private static boolean reject(Runnable action) {
    try {
      action.run();
      return false;
    } catch (IllegalArgumentException expected) {
      return true;
    }
  }
}
