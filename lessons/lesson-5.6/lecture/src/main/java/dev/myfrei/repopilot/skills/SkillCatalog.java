package dev.myfrei.repopilot.skills;

import dev.myfrei.repopilot.agent.AgentJson;
import dev.myfrei.repopilot.ingestion.Hashes;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Deliberately small Agent Skills subset. Reads configured project resources, never home
 * directories.
 */
public final class SkillCatalog {
  public static final int MAX_SKILLS = 4,
      MAX_INSTRUCTION_BYTES = 3072,
      MAX_REFERENCE_BYTES = 2048,
      MAX_REFERENCES = 2;
  private final Path root;

  public SkillCatalog(Path root) {
    Objects.requireNonNull(root);
    Path configured = root.toAbsolutePath().normalize();
    // The configured ancestors are trusted; the conventional .agents component is not followed.
    if (Files.isSymbolicLink(configured)
        || configured.getParent() != null && Files.isSymbolicLink(configured.getParent()))
      throw error("SKILL_UNSAFE_PATH");
    try {
      this.root = configured.toRealPath();
      if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS))
        throw error("SKILL_ROOT_UNAVAILABLE");
    } catch (IOException e) {
      throw error("SKILL_ROOT_UNAVAILABLE");
    }
  }

  public SkillSnapshot snapshot() {
    try {
      if (Files.isSymbolicLink(root) || !root.equals(root.toRealPath()))
        throw error("SKILL_UNSAFE_PATH");
      var bundles = new TreeMap<String, SkillSnapshot.Bundle>();
      try (var children = Files.list(root)) {
        var dirs = children.limit(MAX_SKILLS + 1L).toList();
        if (dirs.size() > MAX_SKILLS) throw error("SKILL_CATALOG_LIMIT");
        for (Path dir : dirs) {
          String name = dir.getFileName().toString();
          validateName(name);
          if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw error("SKILL_UNSAFE_PATH");
          bundles.put(name, readBundle(dir, name));
        }
      }
      return new SkillSnapshot(this, bundles);
    } catch (SkillException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw error("SKILL_CATALOG_UNAVAILABLE");
    }
  }

  private SkillSnapshot.Bundle readBundle(Path directory, String name) throws IOException {
    try (var entries = Files.list(directory)) {
      for (Path entry : entries.limit(4).toList()) {
        if (Files.isSymbolicLink(entry)) throw error("SKILL_UNSAFE_PATH");
        if (!Set.of("SKILL.md", "references").contains(entry.getFileName().toString()))
          throw error("SKILL_UNSUPPORTED_RESOURCE");
      }
    }
    String markdown = readText(directory.resolve("SKILL.md"), MAX_INSTRUCTION_BYTES);
    String normalized = markdown.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n")) throw error("SKILL_INVALID_METADATA");
    int end = normalized.indexOf("\n---\n", 4);
    if (end < 0) throw error("SKILL_INVALID_METADATA");
    String frontmatter = normalized.substring(4, end), body = normalized.substring(end + 5);
    if (body.isBlank()) throw error("SKILL_EMPTY_INSTRUCTIONS");
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(4);
    options.setCodePointLimit(MAX_INSTRUCTION_BYTES);
    Object parsed;
    try {
      parsed = new Yaml(new SafeConstructor(options)).load(frontmatter);
    } catch (RuntimeException invalid) {
      throw error("SKILL_INVALID_METADATA");
    }
    if (!(parsed instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("name", "description")))
      throw error("SKILL_UNSUPPORTED_METADATA");
    if (!(map.get("name") instanceof String declared)
        || !declared.equals(name)
        || !(map.get("description") instanceof String description)
        || description.isBlank()
        || AgentJson.bytes(description) > 256
        || description.codePoints().anyMatch(Character::isISOControl))
      throw error("SKILL_INVALID_METADATA");
    var references = new TreeMap<String, String>();
    Path refs = directory.resolve("references");
    if (Files.exists(refs, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(refs) || !Files.isDirectory(refs, LinkOption.NOFOLLOW_LINKS))
        throw error("SKILL_UNSAFE_PATH");
      try (var entries = Files.list(refs)) {
        var files = entries.limit(MAX_REFERENCES + 1L).toList();
        if (files.size() > MAX_REFERENCES) throw error("SKILL_RESOURCE_LIMIT");
        for (Path file : files) {
          String relative = "references/" + file.getFileName();
          validateReference(relative);
          references.put(relative, readText(file, MAX_REFERENCE_BYTES));
        }
      }
    }
    var manifest = new TreeMap<String, String>();
    manifest.put("SKILL.md", Hashes.sha256(markdown));
    references.forEach((path, text) -> manifest.put(path, Hashes.sha256(text)));
    String version = Hashes.sha256(AgentJson.canonical(manifest));
    var metadata =
        new SkillMetadata(
            name,
            description,
            version,
            AgentJson.bytes(markdown),
            references.values().stream().mapToInt(AgentJson::bytes).sum());
    return new SkillSnapshot.Bundle(metadata, markdown, Map.copyOf(references));
  }

  static void validateName(String name) {
    if (name == null
        || !name.matches("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")
        || name.contains("--")) throw error("SKILL_INVALID_NAME");
  }

  static void validateReference(String path) {
    if (path == null || !path.matches("references/[a-z0-9][a-z0-9-]{0,63}\\.md"))
      throw error("SKILL_REFERENCE_NOT_ALLOWED");
  }

  private String readText(Path path, int max) throws IOException {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw error("SKILL_UNSAFE_PATH");
    if (!path.toRealPath().startsWith(root)) throw error("SKILL_UNSAFE_PATH");
    byte[] bytes;
    try (var in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      bytes = in.readNBytes(max + 1);
    }
    if (bytes.length > max) throw error("SKILL_RESOURCE_LIMIT");
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw error("SKILL_INVALID_UTF8");
    }
  }

  static SkillException error(String code) {
    return new SkillException(code);
  }
}
