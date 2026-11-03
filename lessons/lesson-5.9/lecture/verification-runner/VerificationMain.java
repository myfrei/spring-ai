package dev.course.verifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;

/** Trusted image entrypoint. Target classes are compiled here but executed only in a child JVM. */
public final class VerificationMain {
  public static void main(String[] ignored) throws Exception {
    Path input = Path.of("/input/src/main/java/dev/myfrei/fixture/StockLedger.java");
    Path classes = Files.createDirectories(Path.of("/build/classes"));
    int compilation =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                System.out,
                System.err,
                "-proc:none",
                "--release",
                "25",
                "-encoding",
                "UTF-8",
                "-Xmaxerrs",
                "20",
                "-d",
                classes.toString(),
                input.toString(),
                "/opt/probe/Probe.java");
    if (compilation != 0) {
      System.out.println("COMPILE_FAILED");
      System.exit(10);
    }
    var child =
        new ProcessBuilder(
                "java",
                "-Xmx64m",
                "-XX:+DisableAttachMechanism",
                "-Djava.io.tmpdir=/build",
                "-cp",
                classes.toString(),
                "dev.course.verifier.Probe")
            .start();
    child.getOutputStream().close();
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var limited = new java.util.concurrent.atomic.AtomicBoolean();
    Thread out = Thread.ofVirtual().start(() -> drain(child.getInputStream(), stdout, limited));
    Thread err = Thread.ofVirtual().start(() -> drain(child.getErrorStream(), stderr, limited));
    if (!child.waitFor(12, TimeUnit.SECONDS)) {
      child.destroyForcibly();
      child.waitFor(1, TimeUnit.SECONDS);
      System.out.println("PROBE_TIMEOUT");
      System.exit(124);
    }
    out.join(1000);
    err.join(1000);
    if (limited.get()) System.err.println("PROBE_OUTPUT_TRUNCATED");
    System.err.write(stderr.toByteArray());
    String observed = stdout.toString(StandardCharsets.UTF_8).strip();
    if (child.exitValue() != 0
        || out.isAlive()
        || err.isAlive()
        || !observed.matches(
            "OBS_V1\\|(?:true|false)\\|-?[0-9]+\\|-?[0-9]+\\|(?:true|false)\\|-?[0-9]+\\|(?:true|false)\\|-?[0-9]+\\|(?:true|false)\\|(?:true|false)\\|(?:true|false)\\|(?:true|false)")) {
      System.out.println("PROBE_PROTOCOL_ERROR childExit=" + child.exitValue());
      System.err.write(stderr.toByteArray());
      System.exit(20);
    }
    String[] parts = observed.split("\\|", -1);
    boolean[] checks = {
      parts[1].equals("true") && parts[2].equals("1") && parts[3].equals("2"),
      parts[4].equals("false") && parts[5].equals("3"),
      parts[6].equals("true") && parts[7].equals("0"),
      parts[8].equals("true") && parts[9].equals("true"),
      parts[10].equals("true") && parts[11].equals("true")
    };
    String[] names = {
      "partial-reservation",
      "insufficient-stock-unchanged",
      "exact-stock-reservation",
      "invalid-input-rejected",
      "snapshot-independent-immutable"
    };
    boolean passed = true;
    for (int i = 0; i < checks.length; i++) {
      System.out.println(names[i] + "=" + (checks[i] ? "PASS" : "FAIL"));
      passed &= checks[i];
    }
    System.out.println("STOCKROOM_CONTRACT_V1 checks=5");
    System.exit(passed ? 0 : 10);
  }

  private static void drain(
      InputStream input,
      ByteArrayOutputStream output,
      java.util.concurrent.atomic.AtomicBoolean limited) {
    try (input) {
      byte[] buffer = new byte[1024];
      int n;
      while ((n = input.read(buffer)) != -1) {
        int remaining = 8192 - output.size();
        if (remaining > 0) output.write(buffer, 0, Math.min(remaining, n));
        if (n > remaining) limited.set(true);
      }
    } catch (IOException ignored) {
    }
  }
}
