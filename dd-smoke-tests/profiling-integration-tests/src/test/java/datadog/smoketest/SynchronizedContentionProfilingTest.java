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

import datadog.environment.JavaVirtualMachine;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/**
 * Smoke test for the {@code synchronized-contention} ByteBuddy instrumentation module. Asserts that
 * block-level {@code synchronized(obj){}} and method-level {@code synchronized} contention emit
 * {@code datadog.TaskBlock} events with a non-zero {@code blocker} field on JDK 21+, where the
 * native JVMTI {@code MonitorContendedEnter}/{@code Entered} path is not active.
 *
 * <p>Gated on JDK 21+ because the native JVMTI path covers JDK &lt; 21 (verified by {@code
 * MonitorContendedTaskBlockTest} in {@code ddprof-test}).
 */
@DisabledOnJ9
final class SynchronizedContentionProfilingTest {
  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  private static final IAttribute<IQuantity> BLOCKER =
      attr("blocker", "blocker", "blocker", NUMBER);
  private static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + SynchronizedContentionProfilingTest.class.getName());

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    Assumptions.assumeTrue(
        JavaVirtualMachine.isJavaVersionAtLeast(21),
        "synchronized-contention ByteBuddy instrumentation requires JDK 21+");
    Files.createDirectories(LOG_FILE_BASE);
    logFilePath =
        LOG_FILE_BASE.resolve(
            testInfo.getTestMethod().map(m -> m.getName()).orElse("syncContention") + ".log");
    dumpDir = Files.createTempDirectory("dd-profiler-synccontention-");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dumpDir != null && Files.exists(dumpDir)) {
      Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  @Test
  @DisplayName(
      "synchronized block and method contention emit span-attributed TaskBlock events on JDK 21+")
  void synchronizedContentionEmitsTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();

    // Each scenario must have produced at least one TaskBlock event.
    assertTrue(
        stats.blockScenarioCount > 0,
        "Expected TaskBlock events for synchronized block contention");
    assertTrue(
        stats.instanceMethodScenarioCount > 0,
        "Expected TaskBlock events for synchronized instance-method contention");
    assertTrue(
        stats.staticMethodScenarioCount > 0,
        "Expected TaskBlock events for synchronized static-method contention");

    // Every emitted event must carry a valid span context.
    assertFalse(stats.hasZeroSpanId, "TaskBlock events must have non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId, "TaskBlock events must have non-zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");

    // The blocker field must identify the contested monitor (non-zero).
    assertTrue(stats.blockersWithNonZeroValue > 0, "Expected non-zero blocker on TaskBlock events");

    // The three scenarios contend on three distinct locks, so the blocker values must not all be
    // identical — this proves the rewriter is recording per-monitor identity, not a constant.
    assertTrue(
        stats.distinctBlockerValues.size() > 1,
        "Expected distinct blocker values across the three contention scenarios");

    assertFalse(
        logHasSynchronizedContentionError(),
        "synchronized-contention instrumentation must not produce errors");
  }

  private ProcessBuilder createProcessBuilder() {
    String templateOverride =
        SynchronizedContentionProfilingTest.class
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
                "-Ddd.service.name=smoke-test-synccontention-taskblock",
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
                com.datadog.smoketest.profiling.SynchronizedContentionForkedApp.class.getName()));
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
    for (Path jfrFile : jfrFiles) {
      stats.add(loadEvents(jfrFile));
    }
    return stats;
  }

  private IItemCollection loadEvents(final Path path) {
    try {
      return JfrLoaderToolkit.loadEvents(extractLastJfrStream(path).toFile());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load JFR " + path, e);
    }
  }

  private Path extractLastJfrStream(final Path path) throws IOException {
    byte[] data = Files.readAllBytes(path);
    int lastMagic = lastIndexOf(data, JFR_MAGIC);
    if (lastMagic <= 0) {
      return path;
    }
    Path extracted = dumpDir.resolve(path.getFileName() + ".ddprof.jfr");
    Files.write(extracted, Arrays.copyOfRange(data, lastMagic, data.length));
    return extracted;
  }

  private static int lastIndexOf(final byte[] data, final byte[] needle) {
    for (int i = data.length - needle.length; i >= 0; i--) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }

  private boolean logHasSynchronizedContentionError() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    return log.contains("NoClassDefFoundError")
        || log.contains("Failed to handle exception in instrumentation")
        || log.contains("VerifyError");
  }

  // ------------------------------------------------------------------ stats

  private static final class JfrStats {
    long blockScenarioCount;
    long instanceMethodScenarioCount;
    long staticMethodScenarioCount;
    long blockersWithNonZeroValue;
    final Set<Long> distinctBlockerValues = new HashSet<>();
    boolean hasZeroSpanId;
    boolean hasZeroLocalRootSpanId;
    boolean hasMissingEventThread;

    void add(final IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> spanIdAcc = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootSpanIdAcc =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blockerAcc = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<String, IItem> operationAcc = OPERATION.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadAcc =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        for (IItem item : items) {
          String op = operationAcc == null ? null : operationAcc.getMember(item);
          if (!"sync.block".equals(op)
              && !"sync.instance-method".equals(op)
              && !"sync.static-method".equals(op)) {
            continue;
          }
          if ("sync.block".equals(op)) blockScenarioCount++;
          else if ("sync.instance-method".equals(op)) instanceMethodScenarioCount++;
          else staticMethodScenarioCount++;

          if (spanIdAcc != null) {
            long spanId = spanIdAcc.getMember(item).longValue();
            hasZeroSpanId |= spanId == 0;
          }
          if (rootSpanIdAcc != null) {
            long rootId = rootSpanIdAcc.getMember(item).longValue();
            hasZeroLocalRootSpanId |= rootId == 0;
          }
          if (blockerAcc != null) {
            long blocker = blockerAcc.getMember(item).longValue();
            if (blocker != 0) {
              blockersWithNonZeroValue++;
              distinctBlockerValues.add(blocker);
            }
          }
          String thread = threadAcc == null ? null : threadAcc.getMember(item);
          hasMissingEventThread |= thread == null || thread.isEmpty();
        }
      }
    }
  }
}
