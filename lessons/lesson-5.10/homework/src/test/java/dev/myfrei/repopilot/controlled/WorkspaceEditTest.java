package dev.myfrei.repopilot.controlled;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceEditTest {
  @TempDir Path root;
  WorkspaceService workspace;
  String run;

  @BeforeEach
  void prepare() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/Calc.java"), "class Calc {\n  int value = 1;\n}\n");
    workspace = new WorkspaceService(root);
    run = UUID.randomUUID().toString();
  }

  @Test
  void exactReadVersionCreatesPreviewAndOnlyExplicitApplyChangesBytes() throws Exception {
    var read = workspace.readFile(run, "src/Calc.java", 2, 2);
    var edit =
        workspace.prepareReplacement(
            run, "src/Calc.java", read.fullFileHash(), "value = 1", "value = 2");
    assertThat(Files.readString(root.resolve("src/Calc.java"))).contains("value = 1");
    assertThat(edit.diff()).contains("--- a/src/Calc.java", "-value = 1", "+value = 2");
    workspace.applyReplacement(edit);
    assertThat(workspace.currentHash("src/Calc.java")).isEqualTo(edit.resultHash());
    assertThat(Files.readString(root.resolve("src/Calc.java")))
        .isEqualTo("class Calc {\n  int value = 2;\n}\n");
    assertCode("STALE_PATCH", () -> workspace.applyReplacement(edit));
    try (var files = Files.list(root.resolve("src"))) {
      assertThat(files.toList()).hasSize(1);
    }
  }

  @Test
  void neitherSearchNorOtherRunNorUnreadLinesAuthorizeAnEdit() {
    String hash = workspace.currentHash("src/Calc.java");
    workspace.searchText("value", "src", 5);
    assertCode(
        "RECEIPTS_UNAVAILABLE",
        () -> workspace.prepareReplacement(run, "src/Calc.java", hash, "value = 1", "value = 2"));
    workspace.readFile(run, "src/Calc.java", 1, 1);
    assertCode(
        "PATCH_NOT_READ",
        () -> workspace.prepareReplacement(run, "src/Calc.java", hash, "value = 1", "value = 2"));
    workspace.readFile(UUID.randomUUID().toString(), "src/Calc.java", 2, 2);
    assertCode(
        "PATCH_NOT_READ",
        () -> workspace.prepareReplacement(run, "src/Calc.java", hash, "value = 1", "value = 2"));
  }

  @Test
  void adjacencyCoversMultilineEditButGapDoesNot() throws Exception {
    Files.writeString(root.resolve("src/Calc.java"), "one\ntwo\nthree\n");
    String hash = workspace.currentHash("src/Calc.java");
    workspace.readFile(run, "src/Calc.java", 1, 1);
    workspace.readFile(run, "src/Calc.java", 3, 3);
    assertCode(
        "PATCH_NOT_READ",
        () ->
            workspace.prepareReplacement(
                run, "src/Calc.java", hash, "one\ntwo\nthree", "replacement"));
    workspace.readFile(run, "src/Calc.java", 2, 2);
    assertThat(
            workspace
                .prepareReplacement(run, "src/Calc.java", hash, "one\ntwo\nthree", "replacement")
                .resultText())
        .isEqualTo("replacement\n");
  }

  @Test
  void changedVersionCannotUseOldReceiptAndChangedAfterPreviewIsNeverOverwritten()
      throws Exception {
    String hash = workspace.readFile(run, "src/Calc.java", 1, 3).fullFileHash();
    var edit = workspace.prepareReplacement(run, "src/Calc.java", hash, "value = 1", "value = 2");
    Files.writeString(root.resolve("src/Calc.java"), "// human changed this\n");
    assertCode("STALE_PATCH", () -> workspace.applyReplacement(edit));
    assertCode(
        "STALE_PATCH",
        () -> workspace.prepareReplacement(run, "src/Calc.java", hash, "value = 1", "value = 2"));
    assertCode(
        "PATCH_NOT_UNIQUE",
        () ->
            workspace.prepareReplacement(
                run,
                "src/Calc.java",
                workspace.currentHash("src/Calc.java"),
                "value = 1",
                "value = 2"));
    assertThat(Files.readString(root.resolve("src/Calc.java")))
        .isEqualTo("// human changed this\n");
  }

  @Test
  void duplicateTextNoopEmptyOversizedAndUnsafePathsAreDenied() throws Exception {
    Files.writeString(root.resolve("src/Calc.java"), "same same\n");
    String hash = workspace.readFile(run, "src/Calc.java", 1, 1).fullFileHash();
    assertCode(
        "PATCH_NOT_UNIQUE",
        () -> workspace.prepareReplacement(run, "src/Calc.java", hash, "same", "different"));
    for (String[] parts :
        List.of(
            new String[] {"", "a"},
            new String[] {"same", "same"},
            new String[] {"same", "x".repeat(513)},
            new String[] {"same", "\u0000"}))
      assertCode(
          "INVALID_PATCH",
          () -> workspace.prepareReplacement(run, "src/Calc.java", hash, parts[0], parts[1]));
    assertCode(
        "PATH_NOT_ALLOWED",
        () -> workspace.prepareReplacement(run, "../outside.java", hash, "same", "x"));
  }

  @Test
  void symlinkSwapAfterPreviewIsDeniedWithoutChangingTarget() throws Exception {
    String hash = workspace.readFile(run, "src/Calc.java", 1, 3).fullFileHash();
    var edit = workspace.prepareReplacement(run, "src/Calc.java", hash, "value = 1", "value = 2");
    Path other = root.resolve("outside.txt");
    Files.writeString(other, "private unchanged");
    Files.delete(root.resolve("src/Calc.java"));
    Files.createSymbolicLink(root.resolve("src/Calc.java"), other);
    assertCode("SYMLINK_NOT_ALLOWED", () -> workspace.applyReplacement(edit));
    assertThat(Files.readString(other)).isEqualTo("private unchanged");
  }

  @Test
  void crlfAndCrLinesUseTheSameCoverageAsReadFile() throws Exception {
    for (String separator : List.of("\r\n", "\r")) {
      Files.writeString(
          root.resolve("src/Calc.java"),
          "one" + separator + "two" + separator + "three" + separator);
      String id = UUID.randomUUID().toString();
      String hash = workspace.readFile(id, "src/Calc.java", 1, 1).fullFileHash();
      assertCode(
          "PATCH_NOT_READ",
          () -> workspace.prepareReplacement(id, "src/Calc.java", hash, "two", "TWO"));
      workspace.readFile(id, "src/Calc.java", 2, 2);
      workspace.applyReplacement(
          workspace.prepareReplacement(id, "src/Calc.java", hash, "two", "TWO"));
      assertThat(Files.readString(root.resolve("src/Calc.java")))
          .isEqualTo("one" + separator + "TWO" + separator + "three" + separator);
    }
  }

  @Test
  void fragmentEndingInLineSeparatorNeedsTheEditedLineAndPreservesFollowingUnreadText()
      throws Exception {
    for (String separator : List.of("\n", "\r\n", "\r")) {
      Files.writeString(root.resolve("src/Calc.java"), "one" + separator + "unread" + separator);
      String id = UUID.randomUUID().toString();
      String hash = workspace.readFile(id, "src/Calc.java", 1, 1).fullFileHash();
      workspace.applyReplacement(
          workspace.prepareReplacement(
              id, "src/Calc.java", hash, "one" + separator, "ONE" + separator));
      assertThat(Files.readString(root.resolve("src/Calc.java")))
          .isEqualTo("ONE" + separator + "unread" + separator);
    }
  }

  static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            WorkspaceException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
