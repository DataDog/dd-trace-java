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
 * Builds portable command lines for simple test utilities.
 *
 * <ul>
 *   <li>POSIX uses native system commands.
 *   <li>Windows uses {@link PortableCommandRunner} in a child JVM to emulate their behavior.
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
