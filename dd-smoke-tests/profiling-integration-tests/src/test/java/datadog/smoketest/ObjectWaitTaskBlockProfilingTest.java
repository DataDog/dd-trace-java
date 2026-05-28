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
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
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
 * Smoke test for {@link
 * datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation}. Object.wait is only
 * instrumented on JDK 21+ (pre-21 the method is native, and JVMTI MonitorWait callbacks cover that
 * path natively). This test is therefore gated on JDK 21+ and guards against regressions such as
 * {@code java.lang.Object} being silently excluded from the global ignore trie.
 */
@DisabledOnJ9
final class ObjectWaitTaskBlockProfilingTest {
  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  private static final IAttribute<IQuantity> BLOCKER =
      attr("blocker", "blocker", "blocker", NUMBER);
  private static final IAttribute<IQuantity> UNBLOCKING_SPAN_ID =
      attr("unblockingSpanId", "unblockingSpanId", "unblockingSpanId", NUMBER);
  private static final IAttribute<IQuantity> TASK_BLOCK_EMITTED =
      attr("numTaskBlockEmitted", "numTaskBlockEmitted", "numTaskBlockEmitted", NUMBER);
  private static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "_dd.trace.operation", "_dd.trace.operation", PLAIN_TEXT);
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + ObjectWaitTaskBlockProfilingTest.class.getName());

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    // Object.wait advice only applies on JDK 21+ (wait(long) is native on JDK 8-20).
    Assumptions.assumeTrue(
        JavaVirtualMachine.isJavaVersionAtLeast(21),
        "Object.wait instrumentation requires JDK 21+");
    Files.createDirectories(LOG_FILE_BASE);
    logFilePath =
        LOG_FILE_BASE.resolve(
            testInfo.getTestMethod().map(method -> method.getName()).orElse("objectWait") + ".log");
    dumpDir = Files.createTempDirectory("dd-profiler-objectwait-");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (dumpDir != null && Files.exists(dumpDir)) {
      Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  @Test
  @DisplayName("Object.wait emits span-attributed TaskBlock events on JDK 21+")
  void objectWaitsEmitTaskBlockEvents() throws Exception {
    Process targetProcess = createProcessBuilder().start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = loadStats();
    assertTrue(stats.taskBlockCount > 0, "Expected datadog.TaskBlock events");
    assertTrue(stats.taskBlockEmitted > 0, "Expected numTaskBlockEmitted counter");
    assertTrue(stats.taskBlocksWithNonZeroBlocker > 0, "Expected monitor identity to be recorded");
    assertFalse(stats.hasZeroSpanId, "TaskBlock events must have non-zero spanId");
    assertFalse(
        stats.hasZeroLocalRootSpanId, "TaskBlock events must have non-zero localRootSpanId");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertTrue(
        stats.hasExpectedOperation,
        "Expected TaskBlock events to include the objectwait.active span operation name");
    // notify/notifyAll remain native on JDK 21+, so the unblocking thread can never be
    // identified via BCI; every Object.wait TaskBlock must report unblockingSpanId == 0.
    assertFalse(
        stats.hasNonZeroUnblockingSpanId,
        "Object.wait TaskBlocks must report unblockingSpanId == 0 (notify is still native)");
    assertFalse(logHasObjectWaitInstrumentationError(), "Object.wait instrumentation failed");
  }

  private ProcessBuilder createProcessBuilder() {
    String templateOverride =
        ObjectWaitTaskBlockProfilingTest.class
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
                "-Ddd.service.name=smoke-test-objectwait-taskblock",
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
                ObjectWaitTaskBlockForkedApp.class.getName()));
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

  private IItemCollection loadEvents(Path path) {
    try {
      return JfrLoaderToolkit.loadEvents(extractLastJfrStream(path).toFile());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load JFR " + path, e);
    }
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

  private boolean logHasObjectWaitInstrumentationError() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    return log.contains("NoClassDefFoundError")
        || log.contains("Failed to handle exception in instrumentation for java.lang.Object");
  }

  public static final class ObjectWaitTaskBlockForkedApp {
    private static final int WAIT_ITERATIONS = 20;
    private static final long LONG_WAIT_MILLIS = 50L;
    private static final Object BLOCKER = new Object();

    public static void main(String[] args) throws Exception {
      ObjectWaitTaskBlockForkedApp app = new ObjectWaitTaskBlockForkedApp(GlobalTracer.get());
      app.runActiveSpanWaits();
      app.runSpanlessWaits();
      app.runTooShortWaits();
      Thread.sleep(1500);
    }

    private final Tracer tracer;

    private ObjectWaitTaskBlockForkedApp(Tracer tracer) {
      this.tracer = tracer;
    }

    private void runActiveSpanWaits() throws InterruptedException {
      for (int i = 0; i < WAIT_ITERATIONS; i++) {
        Span span = tracer.buildSpan("objectwait.active").start();
        try (Scope scope = tracer.activateSpan(span)) {
          synchronized (BLOCKER) {
            BLOCKER.wait(LONG_WAIT_MILLIS);
          }
        } finally {
          span.finish();
        }
      }
    }

    private void runSpanlessWaits() throws InterruptedException {
      for (int i = 0; i < WAIT_ITERATIONS; i++) {
        synchronized (BLOCKER) {
          BLOCKER.wait(LONG_WAIT_MILLIS);
        }
      }
    }

    private void runTooShortWaits() throws InterruptedException {
      // Exercises the wait(long, int) -> wait(long) routing on JDK 21+. The native task-block
      // threshold is 1 ms, so a 1-ms request typically rounds out just over it, but the path
      // coverage matters more than the duration here; the assertion focuses on instrumentation
      // not crashing rather than which side of the threshold the interval lands on.
      for (int i = 0; i < WAIT_ITERATIONS; i++) {
        Span span = tracer.buildSpan("objectwait.too-short").start();
        try (Scope scope = tracer.activateSpan(span)) {
          synchronized (BLOCKER) {
            BLOCKER.wait(0L, 1);
          }
        } finally {
          span.finish();
        }
      }
    }
  }

  private static final class JfrStats {
    private long taskBlockCount;
    private long taskBlockEmitted;
    private long taskBlocksWithNonZeroBlocker;
    private boolean hasZeroSpanId;
    private boolean hasZeroLocalRootSpanId;
    private boolean hasMissingEventThread;
    private boolean hasExpectedOperation;
    private boolean hasNonZeroUnblockingSpanId;

    private void add(IItemCollection events) {
      addTaskBlocks(events);
      addWallClockEpochs(events);
    }

    private void addTaskBlocks(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        IMemberAccessor<IQuantity, IItem> spanIdAccessor = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> localRootSpanIdAccessor =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blockerAccessor = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> unblockingSpanIdAccessor =
            UNBLOCKING_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> eventThreadAccessor =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        IMemberAccessor<String, IItem> operationAccessor = OPERATION.getAccessor(items.getType());
        for (IItem item : items) {
          String operation = operationAccessor == null ? null : operationAccessor.getMember(item);
          // Filter strictly to events emitted by our forked app; the JVM may emit other
          // TaskBlock events (LockSupport from agent code, etc.) that we don't want to mix in.
          if (!"objectwait.active".equals(operation) && !"objectwait.too-short".equals(operation)) {
            continue;
          }
          taskBlockCount++;
          long spanId = spanIdAccessor.getMember(item).longValue();
          long localRootSpanId = localRootSpanIdAccessor.getMember(item).longValue();
          long blocker = blockerAccessor.getMember(item).longValue();
          long unblockingSpanId = unblockingSpanIdAccessor.getMember(item).longValue();
          String eventThread =
              eventThreadAccessor == null ? null : eventThreadAccessor.getMember(item);
          hasZeroSpanId |= spanId == 0;
          hasZeroLocalRootSpanId |= localRootSpanId == 0;
          hasMissingEventThread |= eventThread == null || eventThread.isEmpty();
          hasExpectedOperation |= "objectwait.active".equals(operation);
          if (blocker != 0) {
            taskBlocksWithNonZeroBlocker++;
          }
          if (unblockingSpanId != 0) {
            hasNonZeroUnblockingSpanId = true;
          }
        }
      }
    }

    private void addWallClockEpochs(IItemCollection events) {
      IItemCollection epochs = events.apply(ItemFilters.type("datadog.WallClockSamplingEpoch"));
      for (IItemIterable items : epochs) {
        IMemberAccessor<IQuantity, IItem> emittedAccessor =
            TASK_BLOCK_EMITTED.getAccessor(items.getType());
        for (IItem item : items) {
          taskBlockEmitted += emittedAccessor.getMember(item).longValue();
        }
      }
    }
  }
}
