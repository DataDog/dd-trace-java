package datadog.trace.test.util;

import datadog.environment.OperatingSystem;
import datadog.trace.api.internal.VisibleForTesting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds command lines for a small set of command-line utilities so tests can spawn them without
 * depending on the host operating system.
 *
 * <p>On POSIX platforms the native utilities are used directly. Windows has no usable equivalent
 * for any of them — {@code echo} is a {@code cmd.exe} builtin rather than an executable, {@code
 * type} cannot read standard input, and {@code timeout} refuses to run when standard input is
 * redirected — so there the commands are emulated by {@link PortableCommandRunner} in a child JVM.
 *
 * <p>POSIX deliberately keeps the native utilities instead of emulating everywhere: forking a JVM
 * is far more expensive than spawning a small native binary, in both startup time and memory, and
 * effectively all CI runs on Linux — so the cheap path is the one that matters. It is also exactly
 * what these tests spawned before this class existed, which leaves CI behavior unchanged.
 *
 * <p>Both paths are observably identical: {@code cat} copies bytes exactly, {@code sleep} takes a
 * duration in seconds, and {@code echo} terminates its output with the platform line separator.
 * Callers therefore never need to branch on the operating system.
 *
 * <p>Supported commands:
 *
 * <ul>
 *   <li>{@link #echo(String)} writes a value followed by the platform line separator.
 *   <li>{@link #cat()} copies standard input to standard output.
 *   <li>{@link #sleep(long)} waits for the requested number of seconds, then exits with 0.
 *   <li>{@link #runForever()} never exits on its own and must be destroyed by the caller.
 * </ul>
 */
public final class PortableCommand {
  private static final String MIN_HEAP = "-Xms8m";
  private static final String MAX_HEAP = "-Xmx16m";

  /** Windows has no usable native equivalent for any of these commands. */
  private static final boolean EMULATED = OperatingSystem.isWindows();

  private PortableCommand() {}

  public static String[] echo(String value) {
    return echo(value, EMULATED);
  }

  public static String[] cat() {
    return cat(EMULATED);
  }

  public static String[] sleep(long durationSec) {
    return sleep(durationSec, EMULATED);
  }

  public static String[] runForever() {
    return runForever(EMULATED);
  }

  @VisibleForTesting
  static String[] echo(String value, boolean emulated) {
    return emulated ? emulate("echo", value) : new String[] {"echo", value};
  }

  @VisibleForTesting
  static String[] cat(boolean emulated) {
    return emulated ? emulate("cat") : new String[] {"cat"};
  }

  @VisibleForTesting
  static String[] sleep(long durationSec, boolean emulated) {
    if (durationSec < 0) {
      throw new IllegalArgumentException("Sleep duration must not be negative: " + durationSec);
    }
    // The native sleep takes seconds; the emulated runner takes milliseconds.
    return emulated
        ? emulate("sleep", Long.toString(durationSec * 1000))
        : new String[] {"sleep", Long.toString(durationSec)};
  }

  @VisibleForTesting
  static String[] runForever(boolean emulated) {
    return emulated
        ? emulate("sleep", Long.toString(Long.MAX_VALUE))
        : new String[] {"tail", "-f", "/dev/null"};
  }

  private static String[] emulate(String... arguments) {
    Path executable = javaExecutable();
    Path classpath = classpathEntry();

    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.add(MIN_HEAP);
    command.add(MAX_HEAP);
    command.add("-cp");
    command.add(classpath.toString());
    command.add(PortableCommandRunner.class.getName());
    command.addAll(Arrays.asList(arguments));
    return command.toArray(new String[0]);
  }

  private static Path javaExecutable() {
    return javaExecutable(Paths.get(System.getProperty("java.home")));
  }

  @VisibleForTesting
  static Path javaExecutable(Path javaHome) {
    Path bin = javaHome.resolve("bin");
    for (String name : new String[] {"java", "java.exe"}) {
      Path candidate = bin.resolve(name);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not find a Java executable under " + bin);
  }

  private static Path classpathEntry() {
    CodeSource source = PortableCommand.class.getProtectionDomain().getCodeSource();
    try {
      return Paths.get(source.getLocation().toURI());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot determine the classpath of " + PortableCommand.class.getName(), e);
    }
  }
}
