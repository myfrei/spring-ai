package dev.myfrei.repopilot.skills;

import static org.assertj.core.api.Assertions.*;

import dev.myfrei.repopilot.agent.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SkillCatalogTest {
  @TempDir Path root;

  @Test
  void catalogueContainsOnlyMetadataAndStableFullBundleVersion() throws Exception {
    skill("review", "SECRET-INSTRUCTIONS", "REFERENCE-ONE");
    var first = catalog().snapshot();
    var metadata = first.metadata().getFirst();
    assertThat(metadata.name()).isEqualTo("review");
    assertThat(AgentJson.encode(first.metadata()))
        .doesNotContain("SECRET-INSTRUCTIONS", "REFERENCE-ONE");
    assertThat(catalog().snapshot().metadata()).isEqualTo(first.metadata());
    Files.writeString(root.resolve("review/references/check.md"), "CHANGED-REFERENCE");
    assertThat(catalog().snapshot().metadata().getFirst().version())
        .isNotEqualTo(metadata.version());
  }

  @Test
  void activationBytesCountActualEscapedEnvelopeNotRawMarkdown() throws Exception {
    skill("review", "quote: \" and backslash \\ and кириллица", "reference");
    var snapshot = catalog().snapshot();
    var session = new SkillSession(snapshot);
    Object payload = session.activate("review", "EXPLICIT");
    int actual =
        AgentJson.bytes(AgentJson.encode(Map.of("ok", true, "code", "OK", "data", payload)));
    assertThat(snapshot.activationBytes("review"))
        .isEqualTo(actual)
        .isGreaterThan(snapshot.metadata().getFirst().instructionBytes());
  }

  @Test
  void onlyActivationCanEnableReferencesAndRecordsVersionAndReferenceHash() throws Exception {
    skill("review", "body", "reference");
    var snapshot = catalog().snapshot();
    var session = new SkillSession(snapshot);
    assertCode(
        "SKILL_NOT_ACTIVE",
        () ->
            session.readReference(
                "review", snapshot.metadata().getFirst().version(), "references/check.md"));
    var activation = session.activate("review", "MODEL");
    var ref = session.readReference("review", activation.version(), "references/check.md");
    session.readReference("review", activation.version(), "references/check.md");
    assertThat(ref.content()).isEqualTo("reference");
    assertThat(ref.hash()).hasSize(64);
    assertThat(session.activations()).hasSize(1);
    assertThat(session.activations().getFirst().source()).isEqualTo("MODEL");
    assertThat(session.activations().getFirst().references()).hasSize(1);
    assertThatThrownBy(() -> session.activations().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void activationsAreIsolatedBetweenSessionsEvenFromSameSnapshot() throws Exception {
    skill("review", "body", "ref");
    var snapshot = catalog().snapshot();
    var first = new SkillSession(snapshot);
    first.activate("review", "MODEL");
    var second = new SkillSession(snapshot);
    assertCode(
        "SKILL_NOT_ACTIVE",
        () ->
            second.readReference(
                "review", snapshot.metadata().getFirst().version(), "references/check.md"));
  }

  @Test
  void exactVersionIsRequiredAndResourceChangesInvalidatePinnedSnapshot() throws Exception {
    skill("review", "body", "ref");
    var session = new SkillSession(catalog().snapshot());
    var activated = session.activate("review", "MODEL");
    assertCode(
        "STALE_SKILL",
        () -> session.readReference("review", "a".repeat(64), "references/check.md"));
    Files.writeString(root.resolve("review/references/check.md"), "changed");
    assertCode(
        "STALE_SKILL",
        () -> session.readReference("review", activated.version(), "references/check.md"));
    assertThat(session.activations().getFirst().references()).isEmpty();
  }

  @Test
  void changedInstructionsAfterCatalogueDoNotActivate() throws Exception {
    skill("review", "body", "ref");
    var session = new SkillSession(catalog().snapshot());
    Files.writeString(
        root.resolve("review/SKILL.md"), "---\nname: review\ndescription: bounded\n---\nchanged\n");
    assertCode("STALE_SKILL", () -> session.activate("review", "EXPLICIT"));
    assertThat(session.activations()).isEmpty();
  }

  @Test
  void arbitraryUrlsTraversalOtherNamesAndScriptsAreNotResources() throws Exception {
    skill("review", "body", "ref");
    var session = new SkillSession(catalog().snapshot());
    var activated = session.activate("review", "MODEL");
    for (String path :
        List.of(
            "../SKILL.md",
            "https://example.org/data",
            "scripts/run.sh",
            "references/../../x",
            "references/deep/x.md",
            "references/.secret.md"))
      assertCode(
          "SKILL_REFERENCE_NOT_ALLOWED",
          () -> session.readReference("review", activated.version(), path));
    assertCode("SKILL_NOT_FOUND", () -> session.activate("missing", "MODEL"));
    assertCode(
        "SKILL_REFERENCE_NOT_FOUND",
        () -> session.readReference("review", activated.version(), "references/missing.md"));
  }

  @Test
  void directoryNameMustMatchMetadataAndUnsupportedAuthorizationFieldIsRejected() throws Exception {
    skill("review", "body", "ref");
    var p = root.resolve("review/SKILL.md");
    Files.writeString(p, "---\nname: other\ndescription: valid\n---\nbody\n");
    assertCode("SKILL_INVALID_METADATA", () -> catalog().snapshot());
    Files.writeString(
        p, "---\nname: review\ndescription: valid\nallowed-tools: shell\n---\nbody\n");
    assertCode("SKILL_UNSUPPORTED_METADATA", () -> catalog().snapshot());
  }

  @Test
  void safeYamlRejectsDuplicateKeysObjectTagsAndNestedAliasBombs() throws Exception {
    skill("review", "body", "ref");
    var p = root.resolve("review/SKILL.md");
    for (String yaml :
        List.of(
            "name: review\nname: review\ndescription: x",
            "!!java.lang.ProcessBuilder { command: [touch, marker] }",
            "name: review\ndescription: &a [x, *a]")) {
      Files.writeString(p, "---\n" + yaml + "\n---\nbody\n");
      assertThatThrownBy(() -> catalog().snapshot()).isInstanceOf(SkillException.class);
    }
    assertThat(root.resolve("marker")).doesNotExist();
  }

  @Test
  void symlinkFilesDirectoriesAndConventionalRootAreRejected() throws Exception {
    skill("review", "body", "ref");
    Path outside = Files.createTempFile("skill-outside", ".md");
    try {
      Files.delete(root.resolve("review/references/check.md"));
      Files.createSymbolicLink(root.resolve("review/references/check.md"), outside);
      assertCode("SKILL_UNSAFE_PATH", () -> catalog().snapshot());
      Files.delete(root.resolve("review/references/check.md"));
      Files.delete(root.resolve("review/references"));
      Files.createSymbolicLink(root.resolve("review/references"), outside.getParent());
      assertCode("SKILL_UNSAFE_PATH", () -> catalog().snapshot());
    } finally {
      Files.deleteIfExists(outside);
    }
    Path link = root.resolveSibling(root.getFileName() + "-link");
    try {
      Files.createSymbolicLink(link, root);
      assertCode("SKILL_UNSAFE_PATH", () -> new SkillCatalog(link));
    } finally {
      Files.deleteIfExists(link);
    }
  }

  @Test
  void sizeUtf8AndCountLimitsFailBeforeReturningPartialCatalogue() throws Exception {
    skill("review", "body", "ref");
    var file = root.resolve("review/references/check.md");
    Files.write(file, new byte[] {(byte) 0xff});
    assertCode("SKILL_INVALID_UTF8", () -> catalog().snapshot());
    Files.writeString(file, "x".repeat(SkillCatalog.MAX_REFERENCE_BYTES + 1));
    assertCode("SKILL_RESOURCE_LIMIT", () -> catalog().snapshot());
    Files.writeString(file, "good");
    Files.writeString(root.resolve("review/references/two.md"), "x");
    Files.writeString(root.resolve("review/references/three.md"), "x");
    assertCode("SKILL_RESOURCE_LIMIT", () -> catalog().snapshot());
  }

  @Test
  void onlyFourCatalogueEntriesAndNoScriptResourcesAreAccepted() throws Exception {
    for (String name : List.of("one", "two", "three", "four", "five")) skill(name, "body", "ref");
    assertCode("SKILL_CATALOG_LIMIT", () -> catalog().snapshot());
    for (String name : List.of("two", "three", "four", "five"))
      try (var paths = Files.walk(root.resolve(name))) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    Files.createDirectory(root.resolve("one/scripts"));
    assertCode("SKILL_UNSUPPORTED_RESOURCE", () -> catalog().snapshot());
  }

  @Test
  void fullEnvelopeLimitPreventsRecordingActivationThatCannotReachModel() throws Exception {
    skill("review", "\"".repeat(2200), "ref");
    var session = new SkillSession(catalog().snapshot());
    assertCode("SKILL_OUTPUT_LIMIT", () -> session.activate("review", "MODEL"));
    assertThat(session.activations()).isEmpty();
  }

  @Test
  void twoActualProjectSkillsHaveValidSchemasAndBoundedBodies() {
    var snapshot = new SkillCatalog(Path.of("workspace-fixture/.agents/skills")).snapshot();
    assertThat(snapshot.metadata())
        .extracting(SkillMetadata::name)
        .containsExactly("java-code-review", "spring-incident-analysis");
    var session = new SkillSession(snapshot);
    assertThat(session.tools())
        .extracting(AgentTool::name)
        .containsExactly("activateSkill", "readSkillReference");
    for (var metadata : snapshot.metadata())
      assertThat(snapshot.activationBytes(metadata.name())).isLessThanOrEqualTo(4096);
  }

  @Test
  void toolResultsAreClosedTypedAndNeverClaimFilesystemExecution() throws Exception {
    skill("review", "body", "ref");
    var session = new SkillSession(catalog().snapshot());
    var tool = session.tools().getFirst();
    var context = new AgentToolContext(UUID.randomUUID(), UUID.randomUUID());
    assertThat(tool.execute("{\"name\":\"review\",\"approved\":true}", context).code())
        .isEqualTo("SKILL_INVALID_ARGUMENTS");
    var result = tool.execute("{\"name\":\"review\"}", context);
    assertThat(result.ok()).isTrue();
    assertThat(result.content()).contains("version", "body").doesNotContain("permissionGranted");
  }

  private SkillCatalog catalog() {
    return new SkillCatalog(root);
  }

  private void skill(String name, String body, String reference) throws Exception {
    Path dir = root.resolve(name);
    Files.createDirectories(dir.resolve("references"));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: A bounded review procedure\n---\n" + body + "\n");
    Files.writeString(dir.resolve("references/check.md"), reference);
  }

  private static void assertCode(
      String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOf(SkillException.class).hasMessage(code);
  }
}
