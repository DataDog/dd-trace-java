// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.agentShadowJar;
import static datadog.smoketest.SmokeTestUtils.buildDirectory;
import static datadog.smoketest.SmokeTestUtils.javaPath;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import datadog.trace.api.config.ProfilingConfig;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

/** Shared plumbing for native TaskBlock profiling smoke tests (JFR-loading, forked-JVM launch). */
final class TaskBlockProfilingTestSupport {
  static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  static final IAttribute<IQuantity> BLOCKER = attr("blocker", "blocker", "blocker", NUMBER);
  static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);
  private static final Set<String> REQUIRED_TASK_BLOCK_FIELDS =
      new HashSet<>(
          Arrays.asList(
              "startTime",
              "duration",
              "eventThread",
              "blocker",
              "unblockingSpanId",
              "stackTrace",
              "observedBlockingState",
              "spanId",
              "localRootSpanId"));

  private TaskBlockProfilingTestSupport() {}

  static Path buildLogFilePath(Class<?> testClass, TestInfo testInfo, String defaultName)
      throws IOException {
    Path logFileBase = Paths.get(buildDirectory(), "reports", "testProcess." + testClass.getName());
    Files.createDirectories(logFileBase);
    return logFileBase.resolve(
        testInfo.getTestMethod().map(Method::getName).orElse(defaultName) + ".log");
  }

  static Path createDumpDir(String prefix) throws IOException {
    return Files.createTempDirectory(prefix);
  }

  static void deleteRecursively(Path dir) throws IOException {
    if (dir != null && Files.exists(dir)) {
      Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  static ProcessBuilder createTaskBlockProcessBuilder(
      String serviceName, String mainClassName, Path dumpDir, Path logFilePath) throws IOException {
    String templateOverride =
        TaskBlockProfilingTestSupport.class.getClassLoader().getResource("overrides.jfp").getFile();
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                javaPath(),
                "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"),
                "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
                "-javaagent:" + agentShadowJar(),
                "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
                "-Ddd.service.name=" + serviceName,
                "-Ddd.env=smoketest",
                "-Ddd.version=99",
                "-Ddd.profiling.enabled=true",
                "-Ddd.profiling.ddprof.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_AUXILIARY_TYPE + "=async",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED + "=true",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL + "=10ms",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER + "=false",
                "-Ddd.profiling.agentless=false",
                "-Ddd.profiling.start-delay=0",
                "-Ddd." + ProfilingConfig.PROFILING_START_FORCE_FIRST + "=true",
                "-Ddd.profiling.upload.period=1",
                "-Ddd.profiling.hotspots.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED + "=true",
                "-Ddd.profiling.debug.dump_path=" + dumpDir,
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK + "=true",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UnlockCommercialFeatures",
                "-XX:+FlightRecorder",
                "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride,
                "-cp",
                System.getProperty("java.class.path"),
                mainClassName));
    if (System.getenv("SMOKETEST_AGENT_JAR") == null && System.getenv("TEST_LIBASYNC") != null) {
      command.add(
          command.size() - 3,
          "-Ddd."
              + ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH
              + "="
              + System.getenv("TEST_LIBASYNC"));
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));
    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  static List<IItemCollection> loadDumpedEvents(Path dumpDir) throws IOException {
    List<Path> jfrFiles;
    try (Stream<Path> files = Files.walk(dumpDir)) {
      jfrFiles =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".jfr"))
              .sorted()
              .collect(Collectors.toList());
    }
    List<IItemCollection> events = new ArrayList<>();
    List<RuntimeException> loadFailures = new ArrayList<>();
    for (Path jfrFile : jfrFiles) {
      try {
        events.add(loadEvents(dumpDir, jfrFile));
      } catch (RuntimeException e) {
        loadFailures.add(e);
      }
    }
    assertTrue(
        !events.isEmpty(),
        () -> "No readable JFR files found in " + dumpDir + "; failures=" + loadFailures);
    return events;
  }

  static boolean logContainsAny(Path logFilePath, String... needles) throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    for (String needle : needles) {
      if (log.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  static void assertFinalTaskBlockSchema(IItemIterable items) {
    Set<String> actualFields = new HashSet<>();
    items
        .getType()
        .getAccessorKeys()
        .keySet()
        .forEach(accessorKey -> actualFields.add(accessorKey.getIdentifier()));
    assertTrue(
        actualFields.containsAll(REQUIRED_TASK_BLOCK_FIELDS),
        "TaskBlock schema is missing fields. required="
            + REQUIRED_TASK_BLOCK_FIELDS
            + ", actual="
            + actualFields);
  }

  private static IItemCollection loadEvents(Path dumpDir, Path path) {
    try {
      return JfrLoaderToolkit.loadEvents(extractLastJfrStream(dumpDir, path).toFile());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load JFR " + path, e);
    }
  }

  private static Path extractLastJfrStream(Path dumpDir, Path path) throws IOException {
    byte[] data = Files.readAllBytes(path);
    int lastMagic = lastIndexOf(data, JFR_MAGIC);
    if (lastMagic <= 0) {
      return path;
    }
    Path extracted = dumpDir.resolve(path.getFileName() + ".ddprof.jfr");
    Files.write(extracted, Arrays.copyOfRange(data, lastMagic, data.length));
    return extracted;
  }

  private static int lastIndexOf(byte[] data, byte[] needle) {
    for (int i = data.length - needle.length; i >= 0; i--) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }
}
