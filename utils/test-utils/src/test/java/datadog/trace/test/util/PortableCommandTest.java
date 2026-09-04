package datadog.trace.test.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.OperatingSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class PortableCommandTest {
  private static final long TIMEOUT_SECONDS = 20;

  // Both strategies are exercised on every platform so that the emulated path, which only ships
  // on Windows, is still verified by a POSIX CI run.

  @TableTest({
    "scenario           | emulated",
    "native command     | false   ",
    "emulated child JVM | true    "
  })
  void testEcho(boolean emulated) throws Exception {
    assumeStrategySupported(emulated);

    Result result = run(PortableCommand.echo("hello", emulated), null);

    assertEquals(0, result.exitCode, result.error);
    assertEquals("hello" + System.lineSeparator(), result.output);
  }

  @TableTest({
    "scenario           | emulated",
    "native command     | false   ",
    "emulated child JVM | true    "
  })
  void testCat(boolean emulated) throws Exception {
    assumeStrategySupported(emulated);

    Result result = run(PortableCommand.cat(emulated), "copied".getBytes(UTF_8));

    assertEquals(0, result.exitCode, result.error);
    assertEquals("copied", result.output);
  }

  @TableTest({
    "scenario           | emulated",
    "native command     | false   ",
    "emulated child JVM | true    "
  })
  void testSleep(boolean emulated) throws Exception {
    assumeStrategySupported(emulated);

    long start = System.nanoTime();
    Result result = run(PortableCommand.sleep(1, emulated), null);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(0, result.exitCode, result.error);
    // A floor slightly below the requested duration keeps this robust against clock granularity
    // while still failing if sleep is a no-op.
    assertTrue(elapsedMillis >= 900, "slept for only " + elapsedMillis + "ms");
  }

  @TableTest({
    "scenario           | emulated",
    "native command     | false   ",
    "emulated child JVM | true    "
  })
  void testRunForever(boolean emulated) throws Exception {
    assumeStrategySupported(emulated);

    Process process = new ProcessBuilder(PortableCommand.runForever(emulated)).start();
    try {
      assertFalse(process.waitFor(1, SECONDS), "runForever() must not exit on its own");

      process.destroyForcibly();

      assertTrue(process.waitFor(TIMEOUT_SECONDS, SECONDS), "process outlived destroyForcibly()");
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void publicApiSelectsStrategyForCurrentPlatform() {
    boolean emulated = OperatingSystem.isWindows();

    assertArrayEquals(PortableCommand.echo("v", emulated), PortableCommand.echo("v"));
    assertArrayEquals(PortableCommand.cat(emulated), PortableCommand.cat());
    assertArrayEquals(PortableCommand.sleep(10, emulated), PortableCommand.sleep(10));
    assertArrayEquals(PortableCommand.runForever(emulated), PortableCommand.runForever());
  }

  @Test
  void rejectsNegativeSleep() {
    assertThrows(IllegalArgumentException.class, () -> PortableCommand.sleep(-1, false));
    assertThrows(IllegalArgumentException.class, () -> PortableCommand.sleep(-1, true));
  }

  @Test
  void locatesJavaExecutableOfRunningJvm() {
    Path executable = PortableCommand.javaExecutable(Paths.get(System.getProperty("java.home")));

    assertTrue(Files.isRegularFile(executable), executable + " is not a file");
  }

  @Test
  void locatesWindowsJavaExecutable(@TempDir Path javaHome) throws Exception {
    Path bin = Files.createDirectory(javaHome.resolve("bin"));
    Path executable = Files.createFile(bin.resolve("java.exe"));

    assertEquals(executable, PortableCommand.javaExecutable(javaHome));
  }

  @Test
  void failsWhenJavaExecutableIsMissing(@TempDir Path javaHome) throws Exception {
    Files.createDirectory(javaHome.resolve("bin"));

    assertThrows(IllegalStateException.class, () -> PortableCommand.javaExecutable(javaHome));
  }

  private static void assumeStrategySupported(boolean emulated) {
    assumeTrue(
        emulated || !OperatingSystem.isWindows(),
        "native commands are only available on POSIX platforms");
  }

  private static Result run(String[] command, byte[] input) throws Exception {
    Process process = new ProcessBuilder(command).start();
    try {
      // Both streams are drained concurrently: waiting for the process to exit before reading
      // deadlocks as soon as either pipe buffer fills.
      Drain output = Drain.of(process.getInputStream());
      Drain error = Drain.of(process.getErrorStream());

      try (OutputStream stdin = process.getOutputStream()) {
        if (input != null) {
          stdin.write(input);
        }
      }

      assertTrue(
          process.waitFor(TIMEOUT_SECONDS, SECONDS),
          () -> "command timed out: " + String.join(" ", command));
      return new Result(process.exitValue(), output.text(), error.text());
    } finally {
      process.destroyForcibly();
    }
  }

  private static final class Result {
    final int exitCode;
    final String output;
    final String error;

    Result(int exitCode, String output, String error) {
      this.exitCode = exitCode;
      this.output = output;
      this.error = error;
    }
  }

  private static final class Drain extends Thread {
    private final InputStream input;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private volatile IOException failure;

    private Drain(InputStream input) {
      this.input = input;
      setDaemon(true);
    }

    static Drain of(InputStream input) {
      Drain drain = new Drain(input);
      drain.start();
      return drain;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[8192];
      try {
        int read;
        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
        }
      } catch (IOException e) {
        failure = e;
      }
    }

    String text() throws Exception {
      join(SECONDS.toMillis(TIMEOUT_SECONDS));
      if (failure != null) {
        throw failure;
      }
      return new String(output.toByteArray(), UTF_8);
    }
  }
}
