package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.buildDirectory;
import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.SmokeTestUtils.createProcessBuilder;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import datadog.environment.EnvironmentVariables;
import datadog.environment.OperatingSystem;
import datadog.smoketest.profiling.CodeHotspotsApplication;
import datadog.smoketest.profiling.GenerativeStackTraces;
import datadog.smoketest.profiling.NativeLibrariesApplication;
import datadog.trace.test.util.Flaky;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.math3.stat.descriptive.rank.PSquarePercentile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

@DisabledOnJ9
public final class CodeHotspotsTest {
  private static final int TEST_CASE_TIMEOUT = 5; // seconds
  private static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);
  private static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "operation", "operation", PLAIN_TEXT);

  private static final Path LOG_FILE_BASE =
      Paths.get(buildDirectory(), "reports", "testProcess." + CodeHotspotsTest.class.getName());

  @BeforeAll
  static void setupAll() throws Exception {
    assumeFalse(
        OperatingSystem.isMacOs() || EnvironmentVariables.get("TEST_LIBDDPROF") == null,
        "Test skipped. Set TEST_LIBDDPROF env variable to point to MacOS version of libjavaProfiler.so, and rerun.");
    Files.createDirectories(LOG_FILE_BASE);
  }

  private Path logFilePath = null;
  private Path dumpDir = null;

  private int timeout;

  @BeforeEach
  void setup(final TestInfo testInfo) throws Exception {
    logFilePath = LOG_FILE_BASE.resolve(testInfo.getTestMethod().orElse(null).getName() + ".log");
    dumpDir = Files.createTempDirectory("dd-profiler-");

    double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    int cores = Runtime.getRuntime().availableProcessors();
    // if the system is busy the timeout needs to be extended in order for the profiler to run
    double timeoutQuotient = Math.ceil(Math.max(1d, load / cores));

    timeout = (int) (TEST_CASE_TIMEOUT * timeoutQuotient);
    if (timeoutQuotient > 1) {
      System.out.println(
          "===> Timeout scaled by "
              + timeoutQuotient
              + " to "
              + timeout
              + "s (load = "
              + load
              + ", cores = "
              + cores
              + ")");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    Files.walk(dumpDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    Files.deleteIfExists(dumpDir);
    System.out.println("===\n");
  }

  @ParameterizedTest(
      name = "Test reactive app (mean service time = {0}, arrival rate = {1} tasks/s)")
  @MethodSource("reactiveTestParams")
  @Disabled
  void testReactive(Duration meanServiceTime, int arrivalRate, double minCoverage)
      throws Exception {
    System.out.println(
        "=== Test reactive app (mean service time = "
            + meanServiceTime
            + ", arrival rate = "
            + arrivalRate
            + " tasks/s)");
    int interval = 10; // milliseconds
    int workers = 2;
    Process targetProcess =
        createProcessBuilder(
                CodeHotspotsApplication.class.getName(),
                0,
                timeout * 2,
                interval,
                interval,
                dumpDir,
                logFilePath,
                "reactive",
                Integer.toString(workers),
                Long.toString(meanServiceTime.toNanos()),
                Long.toString(arrivalRate),
                Integer.toString(timeout))
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    long serviceRate = (long) (workers * 1_000_000_000d) / meanServiceTime.toNanos();
    double idleness = Math.max(0d, (serviceRate - arrivalRate) / (double) serviceRate);

    Files.walk(dumpDir)
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .forEach(f -> validateJfr(f, idleness, minCoverage));
  }

  private static Stream<Arguments> reactiveTestParams() {
    return Stream.of(
        Arguments.of(Duration.ofMillis(100), 5000, 0.9d),
        Arguments.of(Duration.ofMillis(1), 5000, 0.5d),
        Arguments.of(Duration.of(100, ChronoUnit.MICROS), 5000, 0.2d),
        Arguments.of(Duration.ofMillis(100), 50, 0.8d),
        Arguments.of(Duration.ofMillis(1), 50, 0.3d),
        Arguments.of(Duration.of(100, ChronoUnit.MICROS), 50, 0.15d));
  }

  @Test
  @DisplayName("Test batch app")
  @Flaky
  void testBatch() throws Exception {
    System.out.println("Test batch app");
    int meanServiceTimeSecs = 1; // seconds
    long meanServiceTimeNs = TimeUnit.SECONDS.toNanos(meanServiceTimeSecs);
    int interval = 10; // milliseconds
    Process targetProcess =
        createProcessBuilder(
                CodeHotspotsApplication.class.getName(),
                0,
                timeout * 2,
                interval,
                interval,
                dumpDir,
                logFilePath,
                "batch",
                Long.toString(meanServiceTimeNs),
                Integer.toString(timeout))
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    Files.walk(dumpDir)
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .forEach(f -> validateJfr(f, 0, 0.9));
  }

  @Test
  @DisplayName("Test saturated parallel processing")
  @Flaky
  void testSaturatedFanout() throws Exception {
    System.out.println("Test saturated parallel processing");
    int meanServiceTimeSecs = 1; // seconds
    long meanServiceTimeNs = TimeUnit.SECONDS.toNanos(meanServiceTimeSecs);
    int interval = 10; // milliseconds
    int workers =
        Runtime.getRuntime().availableProcessors() * 2; // more workers than available cores
    Process targetProcess =
        createProcessBuilder(
                CodeHotspotsApplication.class.getName(),
                0,
                timeout * 2,
                interval,
                interval,
                dumpDir,
                logFilePath,
                "fanout",
                Integer.toString(workers),
                Long.toString(meanServiceTimeNs),
                Integer.toString(timeout))
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    Files.walk(dumpDir)
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .forEach(f -> validateJfr(f, 0, 0.8));
  }

  @ParameterizedTest
  @ValueSource(strings = {"lz4", "snappy"})
  @Flaky
  void testNativeLibrary(String libraryName) throws Exception {
    System.out.println("Test " + libraryName);
    int interval = 10; // milliseconds
    Process targetProcess =
        createProcessBuilder(
                NativeLibrariesApplication.class.getName(),
                0,
                timeout * 2,
                interval,
                interval,
                dumpDir,
                logFilePath,
                libraryName)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    Files.walk(dumpDir)
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .forEach(CodeHotspotsTest::hasCpuEvents);
  }

  @Flaky
  @ParameterizedTest
  @ValueSource(ints = {128})
  void testGenerativeStackTraces(int depth) throws Exception {
    runTestGenerativeStackTraces("Raw", depth);
  }

  @Flaky
  @ParameterizedTest
  @ValueSource(ints = {128})
  void testGenerativeStackTracesWithMethodHandles(int depth) throws Exception {
    runTestGenerativeStackTraces("MethodHandles", depth);
  }

  @Flaky("hasCpuEvents assertions fails sometimes")
  @ParameterizedTest
  @ValueSource(ints = {128})
  void testGenerativeStackTracesWithCapturingLambdas(int depth) throws Exception {
    runTestGenerativeStackTraces("CapturingLambdas", depth);
  }

  private void runTestGenerativeStackTraces(String mode, int depth) throws Exception {
    System.out.println("Test depth=" + depth + " with mode: " + mode);
    int interval = 10; // milliseconds
    Process targetProcess =
        createProcessBuilder(
                GenerativeStackTraces.class.getName(),
                0,
                timeout * 2,
                interval,
                interval,
                dumpDir,
                logFilePath,
                String.valueOf(depth),
                "1000",
                mode)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    Files.walk(dumpDir)
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .forEach(CodeHotspotsTest::hasCpuEvents);
  }

  private static void hasCpuEvents(File f) {
    try {
      IItemCollection events = JfrLoaderToolkit.loadEvents(f);
      IItemCollection cpu = events.apply(ItemFilters.type("datadog.ExecutionSample"));
      assertTrue(cpu.hasItems(), "no cpu events");
      int labelCount = 0;
      for (IItemIterable items : cpu) {
        IMemberAccessor<IQuantity, IItem> spanIdAccessor = SPAN_ID.getAccessor(items.getType());
        for (IItem item : items) {
          long spanId = spanIdAccessor.getMember(item).longValue();
          labelCount += (spanId != 0) ? 1 : 0;
        }
      }
      assertTrue(labelCount > 0, "no cpu labels");
    } catch (Exception e) {
      fail(e);
    }
  }

  private static void validateJfr(File f, double idleness, double minCoverage) {
    try {
      IItemCollection events = JfrLoaderToolkit.loadEvents(f);
      IItemCollection wallclock = events.apply(ItemFilters.type("datadog.MethodSample"));
      //      IItemCollection cpu = events.apply(ItemFilters.type("datadog.ExecutionSample"));
      assertTrue(wallclock.hasItems(), "No datadog.MethodSample events found from " + f.getName());
      //      assertTrue(cpu.hasItems());

      validateStats(wallclock, idleness, minCoverage);
    } catch (Exception e) {
      fail(e);
    }
  }

  private static void validateStats(IItemCollection events, double idleness, double minCoverage) {
    SummaryStatistics summaryStats = new SummaryStatistics();
    PSquarePercentile p50 = new PSquarePercentile(50d);
    PSquarePercentile p99 = new PSquarePercentile(1d);
    long qualifiedSamples = 0;
    long nonQualifiedSamples = 0;

    Map<String, AtomicLong> spanSampleCnt = new HashMap<>();
    Map<String, AtomicLong> operationSampleCnt = new HashMap<>();
    for (IItemIterable items : events) {
      IMemberAccessor<IQuantity, IItem> spanIdAccessor = SPAN_ID.getAccessor(items.getType());
      IMemberAccessor<String, IItem> operationNameAccessor = OPERATION.getAccessor(items.getType());
      IMemberAccessor<String, IItem> threadNameAccessor =
          JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
      for (IItem item : items) {
        String threadName = threadNameAccessor.getMember(item);
        if (!threadName.contains("Worker")) {
          continue;
        }
        String operationName = operationNameAccessor.getMember(item);
        long spanId = spanIdAccessor.getMember(item).longValue();
        if (spanId == 0) {
          nonQualifiedSamples++;
        } else {
          qualifiedSamples++;
          spanSampleCnt
              .computeIfAbsent(Long.toString(spanId), k -> new AtomicLong(0))
              .incrementAndGet();
          operationSampleCnt
              .computeIfAbsent(operationName, k -> new AtomicLong(0))
              .incrementAndGet();
        }
      }
    }
    spanSampleCnt.values().stream()
        .map(AtomicLong::get)
        .forEach(
            v -> {
              summaryStats.addValue(v);
              p99.increment(v);
              p50.increment(v);
            });

    /*
     Qualified samples are scaled according to 'idleness' -
     If the system is idle the workers will be waiting for input (preferably, outside any context) and as such
     the ratio of samples without context will be inflated.
     Re-scaling using the idleness metric brings the coverage back to the level as if the system is fully
     utilized.
    */
    double scaledQualifiedSamples = (qualifiedSamples / (1 - idleness));
    double coverage = (scaledQualifiedSamples / (scaledQualifiedSamples + nonQualifiedSamples));
    System.out.println("Spans     : " + summaryStats.getN());
    System.out.println("  Coverage: " + coverage);
    System.out.println("Samples   :");
    System.out.println("  Mean    : " + summaryStats.getMean());
    System.out.println("  Median  : " + p50.getResult());
    System.out.println("  P99     : " + p99.getResult());

    assertTrue(coverage >= minCoverage, "Expected coverage: " + coverage + " >= " + minCoverage);

    // span names defined in CodeHotspotsApplication
    assertFalse(operationSampleCnt.isEmpty(), "no operation names");
    assertTrue(operationSampleCnt.size() <= 2, "too many operation names");
    assertTrue(
        operationSampleCnt.get("top") != null || operationSampleCnt.get("work_item") != null,
        "wrong operation names: " + operationSampleCnt.keySet());
  }
}
