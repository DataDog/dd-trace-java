package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.agentShadowJar;
import static datadog.smoketest.SmokeTestUtils.buildDirectory;
import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.SmokeTestUtils.javaPath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import datadog.trace.api.config.ProfilingConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

/**
 * End-to-end smoke test for the {@code thread-sleep} instrumentation: forks a real JVM with {@code
 * -javaagent:} attached and asserts that {@code Thread.sleep} call sites under an active span emit
 * {@code datadog.TaskBlock} JFR events. The fixture deliberately mixes traced and untraced sleeps
 * and a sub-threshold sleep to exercise both the active-span and duration-filter fast paths in
 * {@link TaskBlockHelper}.
 *
 * <p>Runs on every JDK supported by the rest of the suite. The native JVMTI MonitorWait path covers
 * {@code Object.wait()}, not {@code Thread.sleep}, so this test is the direct coverage guard for
 * sleep call-site instrumentation.
 */
@DisabledOnJ9
final class ThreadSleepTaskBlockProfilingTest {

  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  private static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + ThreadSleepTaskBlockProfilingTest.class.getName());

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    Files.createDirectories(LOG_FILE_BASE);
    logFilePath =
        LOG_FILE_BASE.resolve(
            testInfo.getTestMethod().map(method -> method.getName()).orElse("threadSleep")
                + ".log");
    dumpDir = Files.createTempDirectory("dd-profiler-threadsleep-");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dumpDir != null && Files.exists(dumpDir)) {
      Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  @Test
  @DisplayName("Thread.sleep under an active span emits TaskBlock events with span context")
  void threadSleepEmitsTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();
    assertTrue(
        stats.activeSpanTaskBlockCount > 0,
        "Expected datadog.TaskBlock events from traced Thread.sleep call sites");
    assertFalse(stats.hasZeroSpanId, "Active-span TaskBlock events must carry non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId,
        "Active-span TaskBlock events must carry non-zero localRootSpanId");
    assertTrue(
        stats.hasExpectedOperation,
        "Expected TaskBlock events tagged with the threadsleep.active span operation name");
    assertFalse(
        logHasInstrumentationError(),
        "thread-sleep instrumentation produced classloading or rewrite errors in the forked log");
  }

  private ProcessBuilder createProcessBuilder() {
    String templateOverride =
        ThreadSleepTaskBlockProfilingTest.class
            .getClassLoader()
            .getResource("overrides.jfp")
            .getFile();
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                javaPath(),
                "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"),
                "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
                "-javaagent:" + agentShadowJar(),
                "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
                "-Ddd.service.name=smoke-test-threadsleep-taskblock",
                "-Ddd.env=smoketest",
                "-Ddd.version=99",
                "-Ddd.profiling.enabled=true",
                "-Ddd.profiling.ddprof.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_AUXILIARY_TYPE + "=async",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED + "=true",
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL + "=10ms",
                "-Ddd.profiling.agentless=false",
                "-Ddd.profiling.start-delay=0",
                "-Ddd." + ProfilingConfig.PROFILING_START_FORCE_FIRST + "=true",
                "-Ddd.profiling.upload.period=1",
                "-Ddd.profiling.hotspots.enabled=true",
                "-Ddd." + ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED + "=true",
                "-Ddd.profiling.debug.dump_path=" + dumpDir,
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UnlockCommercialFeatures",
                "-XX:+FlightRecorder",
                "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride,
                "-cp",
                System.getProperty("java.class.path"),
                com.datadog.smoketest.profiling.ThreadSleepTaskBlockForkedApp.class.getName()));
    if (System.getenv("TEST_LIBASYNC") != null) {
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

  private JfrStats loadStats() throws Exception {
    JfrStats stats = new JfrStats();
    List<Path> jfrFiles;
    try (java.util.stream.Stream<Path> files = Files.walk(dumpDir)) {
      jfrFiles =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".jfr"))
              .collect(Collectors.toList());
    }
    int loaded = 0;
    for (Path jfrFile : jfrFiles) {
      IItemCollection events = tryLoadEvents(jfrFile);
      if (events != null) {
        stats.add(events);
        loaded++;
      }
    }
    if (loaded == 0 && !jfrFiles.isEmpty()) {
      // Loud failure if NONE of the dumps were parseable — preserves the original failure mode
      // for genuine infrastructure issues while letting partial-truncation cases through.
      throw new RuntimeException(
          "No JFR file in " + dumpDir + " could be parsed (tried " + jfrFiles.size() + " files)");
    }
    return stats;
  }

  private IItemCollection tryLoadEvents(Path path) {
    // Strategy 1: raw file — this is the most common case. The forked process writes one JFR
    // per file under dump_path; concatenation only happens on certain debug dump paths.
    try {
      return JfrLoaderToolkit.loadEvents(path.toFile());
    } catch (Exception ignored) {
      // fall through
    }
    // Strategy 2: extract last FLR stream, in case the file is a concatenation (debug dumps).
    try {
      Path extracted = extractLastJfrStream(path);
      if (!extracted.equals(path)) {
        return JfrLoaderToolkit.loadEvents(extracted.toFile());
      }
    } catch (Exception ignored) {
      // fall through
    }
    return null; // unparseable — skipped, not fatal
  }

  private Path extractLastJfrStream(Path path) throws IOException {
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

  private boolean logHasInstrumentationError() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    return log.contains("NoClassDefFoundError")
        || log.contains(
            "Failed to handle exception in instrumentation for "
                + com.datadog.smoketest.profiling.ThreadSleepTaskBlockForkedApp.class.getName());
  }

  /** Aggregate counts/flags collected across all JFR streams produced by the forked process. */
  private static final class JfrStats {
    long activeSpanTaskBlockCount;
    boolean hasZeroSpanId;
    boolean hasZeroLocalRootSpanId;
    boolean hasExpectedOperation;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> root = LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> op = OPERATION.getAccessor(items.getType());
        if (span == null || root == null) {
          continue;
        }
        for (IItem item : items) {
          long spanId = span.getMember(item).longValue();
          long rootSpanId = root.getMember(item).longValue();
          String operation = op != null ? op.getMember(item) : null;
          if (spanId == 0L) {
            hasZeroSpanId = true;
          } else {
            // We only count events from the active-span runs; spanless sleeps don't emit a
            // TaskBlock at all (TaskBlockHelper short-circuits).
            activeSpanTaskBlockCount++;
            if (rootSpanId == 0L) {
              hasZeroLocalRootSpanId = true;
            }
            if ("threadsleep.active".equals(operation)) {
              hasExpectedOperation = true;
            }
          }
        }
      }
    }
  }
}
