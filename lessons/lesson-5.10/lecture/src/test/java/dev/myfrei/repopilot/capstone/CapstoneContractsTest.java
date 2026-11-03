package dev.myfrei.repopilot.capstone;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

class CapstoneContractsTest {
  @Test
  void pinnedManifestRejectsTaskShelfUnknownFieldsPathCommandsAndInventedBaseline() {
    var task = CapstoneTaskContract.expected();
    assertThat(CapstoneTaskContract.decode(task.fields())).isEqualTo(task);
    assertThat(CapstoneTaskContract.digest(task)).hasSize(64);
    for (var change :
        List.of(
            Map.entry("id", (Object) "TASK-101"),
            Map.entry("sourcePath", (Object) "../secret"),
            Map.entry("command", (Object) "mvn test"),
            Map.entry("baselineHash", (Object) "a".repeat(64)),
            Map.entry("approved", (Object) true))) {
      var forged = new HashMap<>(task.fields());
      forged.put(change.getKey(), change.getValue());
      assertThatThrownBy(() -> CapstoneTaskContract.decode(forged))
          .hasMessage("CAPSTONE_TASK_CONTRACT");
    }
  }

  @Test
  void everyGateIsMandatoryAndCurrentRevisionLossIsStale() {
    var policy = new CapstoneCompletion();
    var all =
        CapstoneCompletion.REQUIRED.stream().map(code -> new CapstoneGate(code, true)).toList();
    assertThat(policy.state(all)).isEqualTo("DONE");
    for (String code : CapstoneCompletion.REQUIRED) {
      var failed =
          all.stream().map(g -> new CapstoneGate(g.code(), !g.code().equals(code))).toList();
      assertThat(policy.state(failed))
          .isEqualTo(code.equals("CURRENT_VERSION") ? "STALE" : "INCOMPLETE");
    }
  }

  @Test
  void omittingRenamingOrDuplicatingAGateCannotProduceDone() {
    var all =
        new ArrayList<>(
            CapstoneCompletion.REQUIRED.stream()
                .map(code -> new CapstoneGate(code, true))
                .toList());
    all.removeLast();
    assertThatThrownBy(() -> new CapstoneCompletion().state(all))
        .isInstanceOf(IllegalArgumentException.class);
    all.add(all.getFirst());
    assertThatThrownBy(() -> new CapstoneCompletion().state(all))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gatewayAcceptsOnlyLiteralLoopbackAndBoundedToken() {
    for (String url :
        List.of(
            "https://127.0.0.1:8093",
            "http://localhost:8093",
            "http://example.org:8093",
            "http://127.0.0.1:8093/other",
            "http://127.0.0.1:8093/?token=x"))
      assertThatThrownBy(() -> new StockRoomMcpGateway(URI.create(url), "token", Clock.systemUTC()))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StockRoomMcpGateway(
                    URI.create("http://127.0.0.1:8093"), "bad\nheader", Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
