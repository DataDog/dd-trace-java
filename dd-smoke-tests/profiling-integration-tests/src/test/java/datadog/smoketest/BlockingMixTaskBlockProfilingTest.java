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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * End-to-end mixed-blocking smoke / regression / demo test. Combines three roles in one fixture:
 *
 * <ol>
 *   <li><b>Cross-workstream smoke</b>: a single forked JVM under {@code -javaagent:} exercises
 *       {@code Thread.sleep} (WS1), {@code LockSupport.park*} (existing {@code lock-support}),
 *       native {@code synchronized} contention and {@code Selector.select(long)} (WS2B). Each
 *       population's events must be present.
 *   <li><b>NoDoubleBracket</b>: each blocking <em>interval</em> emits exactly one {@code
 *       datadog.TaskBlock} event. Multiple TaskBlocks for the same (thread, start time) point at a
 *       regression in the Java helper paths vs. the native JVMTI path or at overlapping helper
 *       invocations.
 *   <li><b>BlockingMix demo</b>: the forked app is meant to be copy-pasted as a reproducer when
 *       triaging coverage issues. The runbook in the README comment at the top of the class lists
 *       JFR inspection commands and the expected operation-name distribution.
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
 *   # 3. List per-operation counts
 *   jfr print --events "datadog.TaskBlock" {dumpDir}/*.jfr \
 *       | grep -oE "_dd.trace.operation = \"[^\"]+\"" | sort | uniq -c
 *
 *   # 4. Expected (steady state):
 *   #     N=20 blockingmix.sleep
 *   #     N=20 blockingmix.park
 *   #     N=20 blockingmix.sync   (native JVMTI monitor callbacks)
 *   #     N=8  blockingmix.select
 *
 *   # 5. Native counter snapshot:
 *   jfr print --events "datadog.DatadogProfilerConfig" {dumpDir}/*.jfr
 * </pre>
 */
@DisabledOnJ9
final class BlockingMixTaskBlockProfilingTest {

  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  private static final IAttribute<IQuantity> START_TIME =
      attr("startTime", "startTime", "startTime", NUMBER);
  private static final IAttribute<IQuantity> DURATION =
      attr("duration", "duration", "duration", NUMBER);
  private static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);
  private static final IAttribute<String> EVENT_THREAD_NAME =
      attr(
          "eventThread.threadName", "eventThread.threadName", "eventThread.threadName", PLAIN_TEXT);

  private static final String OP_SLEEP = "blockingmix.sleep";
  private static final String OP_PARK = "blockingmix.park";
  private static final String OP_SYNC = "blockingmix.sync";
  private static final String OP_SELECT = "blockingmix.select";

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
  @DisplayName("Mixed sleep+park+sync+select workload emits one TaskBlock per blocking interval")
  void mixedBlockingWorkloadEmitsExpectedPopulations() throws Exception {
    Process targetProcess = createProcessBuilder().start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();

    // ---- Smoke ----: every population must be present.
    assertTrue(
        stats.countByOperation.getOrDefault(OP_SLEEP, 0L) > 0,
        "Expected blockingmix.sleep TaskBlock events (thread-sleep call-site module)");
    assertTrue(
        stats.countByOperation.getOrDefault(OP_PARK, 0L) > 0,
        "Expected blockingmix.park TaskBlock events (existing lock-support module)");
    assertTrue(
        stats.countByOperation.getOrDefault(OP_SELECT, 0L) > 0,
        "Expected blockingmix.select TaskBlock events (WS2B nio-selector module)");
    assertTrue(
        stats.countByOperation.getOrDefault(OP_SYNC, 0L) > 0,
        "Expected blockingmix.sync TaskBlock events (native JVMTI monitor callbacks)");

    // ---- NoDoubleBracket ----: no two TaskBlock events on the same thread with overlapping
    // intervals for the same operation. Double-bracketing manifests as two events with the same
    // start time (Java helper + native callback both firing for the same blocking interval).
    assertFalse(
        stats.hasDuplicateInterval,
        "Detected duplicate TaskBlock events for the same (thread, startTime) — double bracket "
            + "regression. Likely culprit: Java helper and native callback both firing for the "
            + "same blocking population, or nio-selector overlapping with an unforeseen JFR "
            + "bridge subscription. First duplicate: "
            + stats.firstDuplicateDescription);

    // ---- Span context ----: all events must carry non-zero span/root-span IDs.
    assertFalse(
        stats.hasZeroSpanId,
        "TaskBlock events from the mixed workload must all carry non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId,
        "TaskBlock events from the mixed workload must all carry non-zero localRootSpanId");

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

  private static final class JfrStats {
    final Map<String, Long> countByOperation = new HashMap<>();
    boolean hasZeroSpanId;
    boolean hasZeroLocalRootSpanId;
    boolean hasDuplicateInterval;
    String firstDuplicateDescription;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      // Detect double-bracketing: events that share (thread, startTime). Using startTime alone is
      // intentionally strict — overlapping windows on different threads are fine, but two events
      // on the same thread starting at the same wall-clock instant indicate the helper AND
      // native path both fired for one blocking interval.
      Set<String> seenIntervals = new HashSet<>();
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> root = LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> op = OPERATION.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> startTime = START_TIME.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadName = EVENT_THREAD_NAME.getAccessor(items.getType());
        if (span == null || root == null) {
          continue;
        }
        for (IItem item : items) {
          long spanId = span.getMember(item).longValue();
          long rootSpanId = root.getMember(item).longValue();
          String operation = op != null ? op.getMember(item) : null;
          if (spanId == 0L) {
            hasZeroSpanId = true;
            continue;
          }
          if (rootSpanId == 0L) {
            hasZeroLocalRootSpanId = true;
          }
          if (operation != null) {
            countByOperation.merge(operation, 1L, Long::sum);
          }
          if (startTime != null && threadName != null) {
            String key = threadName.getMember(item) + "@" + startTime.getMember(item).longValue();
            if (!seenIntervals.add(key) && firstDuplicateDescription == null) {
              hasDuplicateInterval = true;
              firstDuplicateDescription = key + " op=" + operation;
            }
          }
        }
      }
    }
  }
}
