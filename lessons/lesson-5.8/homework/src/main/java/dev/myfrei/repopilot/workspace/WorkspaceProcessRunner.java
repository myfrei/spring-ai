package dev.myfrei.repopilot.workspace;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal process plumbing. Only WorkspaceService supplies the fixed Git command. */
final class WorkspaceProcessRunner {
  record Output(byte[] bytes, boolean truncated, int exitCode) {}

  private final Duration timeout;
  private final int maxBytes;

  WorkspaceProcessRunner() {
    this(
        Duration.ofSeconds(WorkspaceService.GIT_TIMEOUT_SECONDS),
        WorkspaceService.MAX_PROCESS_BYTES);
  }

  WorkspaceProcessRunner(Duration timeout, int maxBytes) {
    this.timeout = timeout;
    this.maxBytes = maxBytes;
  }

  Output run(List<String> command, Path directory) {
    var builder = new ProcessBuilder(command).directory(directory.toFile());
    builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
    builder
        .environment()
        .putAll(
            Map.of(
                "GIT_CONFIG_NOSYSTEM",
                "1",
                "GIT_CONFIG_GLOBAL",
                "/dev/null",
                "GIT_ATTR_NOSYSTEM",
                "1",
                "GIT_TERMINAL_PROMPT",
                "0",
                "GIT_OPTIONAL_LOCKS",
                "0",
                "LC_ALL",
                "C.UTF-8"));
    Process process = null;
    Thread outThread = null, errThread = null;
    var stdout = new ByteArrayOutputStream();
    var truncated = new AtomicBoolean();
    try {
      process = builder.start();
      process.getOutputStream().close();
      final Process child = process;
      outThread = Thread.ofVirtual().start(() -> drain(child.getInputStream(), stdout, truncated));
      errThread =
          Thread.ofVirtual()
              .start(
                  () ->
                      drain(
                          child.getErrorStream(),
                          new ByteArrayOutputStream(),
                          new AtomicBoolean()));
      if (!process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS))
        throw new WorkspaceException("GIT_TIMEOUT");
      outThread.join(1000);
      errThread.join(1000);
      if (outThread.isAlive() || errThread.isAlive()) throw new WorkspaceException("GIT_TIMEOUT");
      return new Output(stdout.toByteArray(), truncated.get(), process.exitValue());
    } catch (IOException error) {
      throw new WorkspaceException("GIT_UNAVAILABLE");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new WorkspaceException("GIT_INTERRUPTED");
    } finally {
      if (process != null) {
        if (process.isAlive()) {
          try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
          } catch (RuntimeException unavailable) {
            /* OS process enumeration can be unavailable. */
          }
          process.destroyForcibly();
          try {
            process.waitFor(1, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
        try {
          process.getInputStream().close();
          process.getErrorStream().close();
        } catch (IOException ignored) {
        }
      }
      if (outThread != null && outThread.isAlive()) outThread.interrupt();
      if (errThread != null && errThread.isAlive()) errThread.interrupt();
    }
  }

  private void drain(InputStream input, ByteArrayOutputStream output, AtomicBoolean truncated) {
    try (input) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = input.read(buffer)) != -1) {
        int remaining = this.maxBytes - output.size();
        if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
        if (read > remaining) truncated.set(true);
      }
    } catch (IOException ignored) {
      truncated.set(true);
    }
  }
}
