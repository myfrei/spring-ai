package dev.myfrei.repopilot.verification;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal fixed Docker CLI plumbing. Never receives a shell or user command string. */
final class BoundedCommand implements DockerVerificationRunner.Executor {
  public DockerVerificationRunner.Output execute(List<String> argv, Duration timeout, int bound) {
    Process process = null;
    Thread out = null, err = null;
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var truncated = new AtomicBoolean();
    try {
      process = new ProcessBuilder(List.copyOf(argv)).start();
      process.getOutputStream().close();
      var child = process;
      out = Thread.ofVirtual().start(() -> drain(child.getInputStream(), stdout, bound, truncated));
      err = Thread.ofVirtual().start(() -> drain(child.getErrorStream(), stderr, bound, truncated));
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
        return new DockerVerificationRunner.Output(null, "", "", true, true);
      out.join(1000);
      err.join(1000);
      if (out.isAlive() || err.isAlive())
        return new DockerVerificationRunner.Output(null, "", "", true, true);
      return new DockerVerificationRunner.Output(
          process.exitValue(), decode(stdout), decode(stderr), truncated.get(), false);
    } catch (IOException unavailable) {
      return new DockerVerificationRunner.Output(null, "", "Docker CLI unavailable", false, false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new DockerVerificationRunner.Output(null, "", "Interrupted", false, true);
    } finally {
      if (process != null) {
        if (process.isAlive()) {
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
      if (out != null && out.isAlive()) out.interrupt();
      if (err != null && err.isAlive()) err.interrupt();
    }
  }

  private static void drain(
      InputStream input, ByteArrayOutputStream output, int max, AtomicBoolean truncated) {
    try (input) {
      byte[] chunk = new byte[1024];
      int n;
      while ((n = input.read(chunk)) != -1) {
        int left = max - output.size();
        if (left > 0) output.write(chunk, 0, Math.min(left, n));
        if (n > left) truncated.set(true);
      }
    } catch (IOException ignored) {
      truncated.set(true);
    }
  }

  private static String decode(ByteArrayOutputStream out) {
    return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
  }
}
