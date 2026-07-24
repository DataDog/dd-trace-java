// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.agentShadowJar;
import static datadog.smoketest.SmokeTestUtils.buildDirectory;
import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.SmokeTestUtils.javaPath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.test.util.Flaky;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/**
 * End-to-end mixed-blocking smoke / regression / demo test. Combines three roles in one fixture:
 *
 * <ol>
 *   <li><b>Cross-workstream smoke</b>: a single forked JVM under {@code -javaagent:} exercises
 *       {@code Thread.sleep}, {@code LockSupport.park*}, and native {@code synchronized}
 *       contention. Each population's events must be present.
 *   <li><b>NoDoubleBracket</b>: each blocking <em>interval</em> emits exactly one {@code
 *       datadog.TaskBlock} event. Overlapping TaskBlocks on one scenario thread point at a
 *       regression in the Java helper paths vs. the native JVMTI path.
 *   <li><b>BlockingMix demo</b>: the forked app is meant to be copy-pasted as a reproducer when
 *       triaging coverage issues. The runbook below lists JFR inspection commands and the expected
 *       scenario thread-name distribution.
 * </ol>
 *
 * <h3>Demo runbook (manual, off-CI)</h3>
 *
 * <pre>
 *   # 1. Run the forked app standalone to produce a JFR
 *   ./gradlew :dd-smoke-tests:profiling-integration-tests:test \
 *       --tests "*BlockingMixTaskBlockProfilingTest*" \
 *       -Ddatadog.forkedTestRetainDumps=true
 *
 *   # 2. Inspect populations
 *   jfr summary {dumpDir}/*.jfr | grep -E "datadog.TaskBlock|wall=" -A1
 *
 *   # 3. List per-scenario thread counts
 *   jfr print --events "datadog.TaskBlock" {dumpDir}/*.jfr \
 *       | grep -oE "eventThread = \\{[^}]+\\}" | sort | uniq -c
 *
 *   # 4. Expected (steady state):
 *   #     N>=20 blockingmix-sleep
 *   #     N=20  blockingmix-park
 *   #     N=20  blockingmix-sync   (native JVMTI monitor callbacks)
 *
 *   # 5. Native counter snapshot:
 *   jfr print --events "datadog.DatadogProfilerConfig" {dumpDir}/*.jfr
 * </pre>
 */
@DisabledOnJ9
@Flaky(
    "TaskBlock/wall-clock sampler intermittently produces zero events across JDK versions; root cause is tracked separately")
final class BlockingMixTaskBlockProfilingTest {

  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  private static final IAttribute<IQuantity> START_TIME =
      attr("startTime", "startTime", "startTime", NUMBER);
  private static final IAttribute<IQuantity> DURATION =
      attr("duration", "duration", "duration", NUMBER);
  private static final String THREAD_SLEEP = "blockingmix-sleep";
  private static final String THREAD_PARK = "blockingmix-park";
  private static final String THREAD_SYNC = "blockingmix-sync";

  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + BlockingMixTaskBlockProfilingTest.class.getName());

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    Files.createDirectories(LOG_FILE_BASE);
    logFilePath =
        LOG_FILE_BASE.resolve(
            testInfo.getTestMethod().map(method -> method.getName()).orElse("blockingMix")
                + ".log");
    dumpDir = Files.createTempDirectory("dd-profiler-blockingmix-");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dumpDir != null && Files.exists(dumpDir)) {
      Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  @Test
  @DisplayName("Mixed sleep+park+sync workload emits one TaskBlock per blocking interval")
  void mixedBlockingWorkloadEmitsExpectedPopulations() throws Exception {
    Process targetProcess = createProcessBuilder().start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);
    assumeDdprofNativeLibraryAvailable();

    JfrStats stats = loadStats();

    // ---- Smoke ----: every population must be present.
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_SLEEP, 0L) > 0,
        "Expected blockingmix.sleep TaskBlock events (thread-sleep call-site module); observed "
            + stats.allCountByThread);
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_PARK, 0L) > 0,
        "Expected blockingmix.park TaskBlock events (existing lock-support module)");
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_SYNC, 0L) > 0,
        "Expected blockingmix.sync TaskBlock events (native JVMTI monitor callbacks)");

    // ---- NoDoubleBracket ----: no two TaskBlock events on the same thread with overlapping
    // intervals for the same operation. This catches shifted start times as well as exact
    // duplicates when Java and native paths both bracket one blocking interval.
    assertFalse(
        stats.hasOverlappingInterval(),
        "Detected overlapping TaskBlock events on one scenario thread — double bracket regression. "
            + "First overlap: "
            + stats.firstOverlapDescription);

    // ---- Span context ----: all TaskBlock events in this workload must be spanless.
    assertFalse(
        stats.hasNonZeroSpanId,
        "TaskBlock events from the mixed workload must all carry zero spanId");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId,
        "TaskBlock events from the mixed workload must all carry zero localRootSpanId");

    // ---- Health ----: no instrumentation classloading or rewrite failures in the forked log.
    assertFalse(
        logHasInstrumentationError(),
        "Instrumentation produced classloading / rewrite errors in the forked log");
  }

  // ------------------------------------------------------------------------------------------
  // Process / JFR plumbing
  // ------------------------------------------------------------------------------------------

  private ProcessBuilder createProcessBuilder() {
    String templateOverride =
        BlockingMixTaskBlockProfilingTest.class
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
                "-Ddd.service.name=smoke-test-blockingmix-taskblock",
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
                "-Ddd." + ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK + "=true",
                "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UnlockCommercialFeatures",
                "-XX:+FlightRecorder",
                "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride,
                "-cp",
                System.getProperty("java.class.path"),
                com.datadog.smoketest.profiling.BlockingMixForkedApp.class.getName()));
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
      throw new RuntimeException(
          "No JFR file in " + dumpDir + " could be parsed (tried " + jfrFiles.size() + " files)");
    }
    return stats;
  }

  private IItemCollection tryLoadEvents(Path path) {
    try {
      return JfrLoaderToolkit.loadEvents(path.toFile());
    } catch (Exception ignored) {
      // fall through
    }
    try {
      Path extracted = extractLastJfrStream(path);
      if (!extracted.equals(path)) {
        return JfrLoaderToolkit.loadEvents(extracted.toFile());
      }
    } catch (Exception ignored) {
      // fall through
    }
    return null;
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
        || log.contains("Failed to handle exception in instrumentation for");
  }

  private void assumeDdprofNativeLibraryAvailable() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    assumeFalse(
        log.contains("libjavaProfiler") && log.contains("not found on classpath"),
        "ddprof native library is not available on this platform");
  }

  private static final class JfrStats {
    final Map<String, Long> countByThread = new HashMap<>();
    final Map<String, Long> allCountByThread = new HashMap<>();
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    final Map<String, List<Interval>> intervalsByThread = new HashMap<>();
    String firstOverlapDescription;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> root = LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> startTime = START_TIME.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> duration = DURATION.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadName =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        for (IItem item : items) {
          String thread = threadName == null ? null : threadName.getMember(item);
          allCountByThread.merge(String.valueOf(thread), 1L, Long::sum);
          if (!THREAD_SLEEP.equals(thread)
              && !THREAD_PARK.equals(thread)
              && !THREAD_SYNC.equals(thread)) {
            continue;
          }
          countByThread.merge(thread, 1L, Long::sum);
          if (span != null) {
            long spanId = span.getMember(item).longValue();
            hasNonZeroSpanId |= spanId != 0L;
          }
          if (root != null) {
            long rootSpanId = root.getMember(item).longValue();
            hasNonZeroLocalRootSpanId |= rootSpanId != 0L;
          }
          if (startTime != null && duration != null && threadName != null) {
            long startNanos = startTime.getMember(item).clampedLongValueIn(UnitLookup.EPOCH_NS);
            long durationNanos = duration.getMember(item).clampedLongValueIn(UnitLookup.NANOSECOND);
            intervalsByThread
                .computeIfAbsent(thread, ignored -> new ArrayList<>())
                .add(new Interval(startNanos, saturatedAdd(startNanos, durationNanos)));
          }
        }
      }
    }

    private boolean hasOverlappingInterval() {
      for (Map.Entry<String, List<Interval>> entry : intervalsByThread.entrySet()) {
        List<Interval> intervals = entry.getValue();
        intervals.sort(Comparator.comparingLong(interval -> interval.startNanos));
        for (int i = 1; i < intervals.size(); i++) {
          Interval previous = intervals.get(i - 1);
          Interval current = intervals.get(i);
          if (current.startNanos < previous.endNanos) {
            firstOverlapDescription =
                entry.getKey()
                    + " ["
                    + previous.startNanos
                    + ","
                    + previous.endNanos
                    + ") overlaps ["
                    + current.startNanos
                    + ","
                    + current.endNanos
                    + ")";
            return true;
          }
        }
      }
      return false;
    }

    private static long saturatedAdd(long left, long right) {
      if (right > 0L && left > Long.MAX_VALUE - right) {
        return Long.MAX_VALUE;
      }
      return left + right;
    }
  }

  private static final class Interval {
    private final long startNanos;
    private final long endNanos;

    private Interval(long startNanos, long endNanos) {
      this.startNanos = startNanos;
      this.endNanos = endNanos;
    }
  }
}
