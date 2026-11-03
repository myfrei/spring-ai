package dev.myfrei.repopilot.research;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResearchReportsTest {
  @TempDir Path root;

  @Test
  void childReceiptValidatesProvenanceButParentMustReadIndependently() throws Exception {
    var workspace = workspace();
    UUID child = UUID.randomUUID(), parent = UUID.randomUUID();
    var read = workspace.readFile(child.toString(), "src/main/A.java", 1, 2);
    var report = ResearchReports.parse(json(read.fullFileHash(), 1, 2), child, workspace);
    assertThat(report.status()).isEqualTo("CANDIDATE");
    assertThat(report.findings().getFirst().statement()).contains("Непроверенная");
    assertThat(ResearchReports.parentGate(parent, List.of(report), workspace))
        .isEqualTo("RESEARCH_NOT_RECHECKED");
    workspace.readFile(parent.toString(), "src/main/A.java", 1, 1);
    assertThat(ResearchReports.parentGate(parent, List.of(report), workspace))
        .isEqualTo("RESEARCH_NOT_RECHECKED");
    workspace.readFile(parent.toString(), "src/main/A.java", 2, 2);
    assertThat(ResearchReports.parentGate(parent, List.of(report), workspace)).isNull();
    Files.writeString(root.resolve("src/main/A.java"), "changed\n");
    assertThat(ResearchReports.parentGate(parent, List.of(report), workspace))
        .isEqualTo("RESEARCH_EVIDENCE_STALE");
  }

  @Test
  void inventedHashUnreadRangeOtherRunAndChangedVersionCannotBecomeCandidate() throws Exception {
    var workspace = workspace();
    UUID child = UUID.randomUUID();
    var read = workspace.readFile(child.toString(), "src/main/A.java", 1, 1);
    for (String answer : List.of(json("a".repeat(64), 1, 1), json(read.fullFileHash(), 1, 2)))
      assertThatThrownBy(() -> ResearchReports.parse(answer, child, workspace))
          .hasMessage("RESEARCH_REPORT_INVALID");
    assertThatThrownBy(
            () ->
                ResearchReports.parse(
                    json(read.fullFileHash(), 1, 1), UUID.randomUUID(), workspace))
        .hasMessage("RESEARCH_REPORT_INVALID");
    Files.writeString(root.resolve("src/main/A.java"), "changed\n");
    assertThatThrownBy(
            () -> ResearchReports.parse(json(read.fullFileHash(), 1, 1), child, workspace))
        .hasMessage("RESEARCH_REPORT_INVALID");
  }

  @Test
  void closedJsonBoundsAndUncertaintyTypesAreEnforced() throws Exception {
    var workspace = workspace();
    UUID child = UUID.randomUUID();
    for (String answer :
        List.of(
            "text",
            "{\"findings\":[],\"uncertainties\":[],\"approved\":true}",
            "{\"findings\":[],\"uncertainties\":[1]}",
            "{\"findings\":[],\"uncertainties\":[\"" + "x".repeat(161) + "\"]}",
            "x".repeat(2049)))
      assertThatThrownBy(() -> ResearchReports.parse(answer, child, workspace))
          .hasMessage("RESEARCH_REPORT_INVALID");
    assertThat(
            ResearchReports.parse(
                    "{\"findings\":[],\"uncertainties\":[\"Недостаточно данных\"]}",
                    child,
                    workspace)
                .findings())
        .isEmpty();
  }

  @Test
  void coverageDoesNotBridgeHolesOrMixVersionsAndPaths() {
    UUID run = UUID.randomUUID();
    String hash = "a".repeat(64);
    var finding = new ResearchFinding("hypothesis", "src/main/A.java", hash, 1, 3);
    var receipts =
        List.of(
            new ReadReceipt(run.toString(), finding.path(), hash, 1, 1),
            new ReadReceipt(run.toString(), finding.path(), hash, 3, 3));
    assertThat(ResearchReports.covered(receipts, run, finding)).isFalse();
    var joined = new ArrayList<>(receipts);
    joined.add(new ReadReceipt(run.toString(), finding.path(), hash, 2, 2));
    assertThat(ResearchReports.covered(joined, run, finding)).isTrue();
  }

  private WorkspaceService workspace() throws Exception {
    Files.createDirectories(root.resolve("src/main"));
    Files.writeString(root.resolve("src/main/A.java"), "class A {\n int count;\n}\n");
    return new WorkspaceService(root);
  }

  private static String json(String hash, int start, int end) {
    return AgentJson.encode(
        Map.of(
            "findings",
            List.of(
                Map.of(
                    "statement",
                    "Непроверенная гипотеза о count",
                    "path",
                    "src/main/A.java",
                    "fullFileHash",
                    hash,
                    "startLine",
                    start,
                    "endLine",
                    end)),
            "uncertainties",
            List.of("Тесты не запускались")));
  }
}
