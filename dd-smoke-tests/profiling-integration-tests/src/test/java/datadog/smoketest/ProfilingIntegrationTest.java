package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.testing.ProfilingTestUtils;
import com.google.common.collect.Multimap;
import datadog.trace.api.Pair;
import datadog.trace.api.config.ProfilingConfig;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.Aggregators;
import org.openjdk.jmc.common.item.Attribute;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProfilingIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(ProfilingIntegrationTest.class);

  @FunctionalInterface
  private interface TestBody {
    void run() throws Exception;
  }

  private static final String VALID_API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final String BOGUS_API_KEY = "bogus";
  private static final int PROFILING_START_DELAY_SECONDS = 1;
  private static final int PROFILING_UPLOAD_PERIOD_SECONDS = 5;
  // Set the request timeout value to the sum of the initial delay and the upload period
  // multiplied by a safety margin
  private static final int SAFETY_MARGIN = 3;
  private static final int REQUEST_WAIT_TIMEOUT =
      (PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS) * SAFETY_MARGIN;

  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(), "reports", "testProcess." + ProfilingIntegrationTest.class.getName());

  private MockWebServer profilingServer;
  private MockWebServer tracingServer;

  private Process targetProcess = null;
  private Path logFilePath = null;

  @BeforeAll
  static void setupAll() throws Exception {
    Files.createDirectories(LOG_FILE_BASE);
  }

  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    tracingServer = new MockWebServer();
    profilingServer = new MockWebServer();
    tracingServer.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            return new MockResponse().setResponseCode(200);
          }
        });
    tracingServer.start();
    profilingServer.start();

    profilingServer.enqueue(new MockResponse().setResponseCode(200));

    logFilePath = LOG_FILE_BASE.resolve(testInfo.getDisplayName() + ".log");
  }

  @AfterEach
  void teardown() throws Exception {
    if (targetProcess != null) {
      targetProcess.destroyForcibly();
    }
    try {
      profilingServer.shutdown();
    } finally {
      tracingServer.shutdown();
    }
  }

  @Test
  @DisplayName("Test continuous recording - no jmx delay")
  public void testContinuousRecording_no_jmx_delay(TestInfo testInfo) throws Exception {
    testWithRetry(() -> testContinuousRecording(0), testInfo, 5);
  }

  @Test
  @DisplayName("Test continuous recording - 1 sec jmx delay")
  public void testContinuousRecording(TestInfo testInfo) throws Exception {
    testWithRetry(() -> testContinuousRecording(1), testInfo, 5);
  }

  private void testContinuousRecording(int jmxFetchDelay) throws Exception {
    try {
      targetProcess = createDefaultProcessBuilder(jmxFetchDelay, logFilePath).start();

      RecordedRequest firstRequest = retrieveRequest();

      assertNotNull(firstRequest);
      Multimap<String, Object> firstRequestParameters =
          ProfilingTestUtils.parseProfilingRequestParameters(firstRequest);

      assertEquals(profilingServer.getPort(), firstRequest.getRequestUrl().url().getPort());
      assertEquals("jfr", getStringParameter("format", firstRequestParameters));
      assertEquals("jfr-continuous", getStringParameter("type", firstRequestParameters));
      assertEquals("jvm", getStringParameter("runtime", firstRequestParameters));

      Instant firstStartTime =
          Instant.parse(getStringParameter("recording-start", firstRequestParameters));
      Instant firstEndTime =
          Instant.parse(getStringParameter("recording-end", firstRequestParameters));
      assertNotNull(firstStartTime);
      assertNotNull(firstEndTime);

      long duration = firstEndTime.toEpochMilli() - firstStartTime.toEpochMilli();
      assertTrue(duration > TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS - 2));
      assertTrue(duration < TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS + 2));

      Map<String, String> requestTags =
          ProfilingTestUtils.parseTags(firstRequestParameters.get("tags[]"));
      assertEquals("smoke-test-java-app", requestTags.get("service"));
      assertEquals("jvm", requestTags.get("language"));
      assertNotNull(requestTags.get("runtime-id"));
      assertEquals(InetAddress.getLocalHost().getHostName(), requestTags.get("host"));

      byte[] byteData = getParameter("chunk-data", byte[].class, firstRequestParameters);
      assertNotNull(byteData);

      assertFalse(logHasErrors(logFilePath));
      IItemCollection events = JfrLoaderToolkit.loadEvents(new ByteArrayInputStream(byteData));
      assertTrue(events.hasItems());
      Pair<Instant, Instant> rangeStartAndEnd = getRangeStartAndEnd(events);
      Instant firstRangeStart = rangeStartAndEnd.getLeft();
      Instant firstRangeEnd = rangeStartAndEnd.getRight();
      assertTrue(
          firstStartTime.compareTo(firstRangeStart) <= 0,
          () ->
              "First range start "
                  + firstRangeStart
                  + " is before first start time "
                  + firstStartTime);

      RecordedRequest nextRequest = retrieveRequest();
      assertNotNull(nextRequest);

      Multimap<String, Object> secondRequestParameters =
          ProfilingTestUtils.parseProfilingRequestParameters(nextRequest);
      Instant secondStartTime =
          Instant.parse(getStringParameter("recording-start", secondRequestParameters));
      Instant secondEndTime =
          Instant.parse(getStringParameter("recording-end", secondRequestParameters));
      long period = secondStartTime.toEpochMilli() - firstStartTime.toEpochMilli();
      long upperLimit = TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS) * 2;
      assertTrue(
          period > 0 && period <= upperLimit,
          () -> "Upload period = " + period + "ms, expected (0, " + upperLimit + "]ms");

      byteData = getParameter("chunk-data", byte[].class, secondRequestParameters);
      assertNotNull(byteData);
      events = JfrLoaderToolkit.loadEvents(new ByteArrayInputStream(byteData));
      assertTrue(events.hasItems());
      rangeStartAndEnd = getRangeStartAndEnd(events);
      Instant secondRangeStart = rangeStartAndEnd.getLeft();
      Instant secondRangeEnd = rangeStartAndEnd.getRight();
      // So the OracleJdkOngoingRecording and underlying recording seems to
      // either lose precision in the reported times, or filter a bit differently
      // so we can't check these invariants =(
      if (!System.getProperty("java.vendor").contains("Oracle")
          || !System.getProperty("java.version").contains("1.8")) {
        assertTrue(
            secondStartTime.compareTo(secondRangeStart) <= 0,
            () ->
                "Second range start "
                    + secondRangeStart
                    + " is before second start time "
                    + secondStartTime);
        assertTrue(
            firstEndTime.isBefore(secondRangeStart),
            () ->
                "Second range start "
                    + secondRangeStart
                    + " is before or equal to first end time "
                    + firstEndTime);
      }
      // Only non-Oracle JDK 8+ JVMs support custom DD events
      if (!System.getProperty("java.vendor").contains("Oracle")
          || !System.getProperty("java.version").contains("1.8")) {
        assertRecordingEvents(events);
      }
    } finally {
      if (targetProcess != null) {
        targetProcess.destroyForcibly();
      }
      targetProcess = null;
    }
  }

  private Instant convertFromQuantity(IQuantity instant) {
    Instant converted = null;
    try {
      IQuantity rangeS = instant.in(UnitLookup.EPOCH_S);
      long es = rangeS.longValue();
      long ns = instant.longValueIn(UnitLookup.EPOCH_NS) - rangeS.longValueIn(UnitLookup.EPOCH_NS);
      converted = Instant.ofEpochSecond(es, ns);
    } catch (QuantityConversionException ignore) {
    }
    return converted;
  }

  private Pair<Instant, Instant> getRangeStartAndEnd(IItemCollection events) {
    return events.getUnfilteredTimeRanges().stream()
        .map(
            range -> {
              Instant convertedStart = convertFromQuantity(range.getStart());
              Instant convertedEnd = convertFromQuantity(range.getEnd());
              return Pair.of(convertedStart, convertedEnd);
            })
        .reduce(
            Pair.of(null, null),
            (send, newSend) -> {
              Instant start = send.getLeft();
              Instant end = send.getRight();
              Instant newStart = newSend.getLeft();
              Instant newEnd = newSend.getRight();
              start =
                  null == start
                      ? newStart
                      : null == newStart ? start : newStart.isBefore(start) ? newStart : start;
              end =
                  null == end ? newEnd : null == newEnd ? end : newEnd.isAfter(end) ? newEnd : end;
              return Pair.of(start, end);
            });
  }

  @Test
  @DisplayName("Test bogus API key")
  void testBogusApiKey(TestInfo testInfo) throws Exception {
    testWithRetry(
        () -> {
          int exitDelay = PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS * 2 + 1;

          try {
            targetProcess =
                createProcessBuilder(
                        BOGUS_API_KEY,
                        0,
                        PROFILING_START_DELAY_SECONDS,
                        PROFILING_UPLOAD_PERIOD_SECONDS,
                        exitDelay,
                        logFilePath)
                    .start();

            /* API key of an incorrect format will cause profiling to get disabled.
              This means no upload requests will be made. We are going to check the log file for
              the presence of a specific message. For this we need to wait for a particular message indicating
              that the profiling system is initializing and then assert for the presence of the expected message
              caused by the API key format error.
            */
            long ts = System.nanoTime();
            while (!checkLogLines(
                logFilePath, line -> line.contains("Registering scope event factory"))) {
              Thread.sleep(500);
              // Wait at most 30 seconds
              if (System.nanoTime() - ts > 30_000_000_000L) {
                throw new TimeoutException();
              }
            }

            /* An API key with incorrect format will cause profiling to get disabled and the
              following message would be logged.
              The test asserts for the presence of the message.
            */
            assertTrue(
                checkLogLines(
                    logFilePath,
                    it -> it.contains("Profiling: API key doesn't match expected format")));
            assertFalse(logHasErrors(logFilePath));
          } finally {
            if (targetProcess != null) {
              targetProcess.destroyForcibly();
              targetProcess = null;
            }
          }
        },
        testInfo,
        3);
  }

  @Test
  @DisplayName("Test shutdown")
  void testShutdown(TestInfo testInfo) throws Exception {
    testWithRetry(
        () -> {
          int exitDelay = PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS * 4 + 1;
          try {
            targetProcess =
                createProcessBuilder(
                        VALID_API_KEY,
                        0,
                        PROFILING_START_DELAY_SECONDS,
                        PROFILING_UPLOAD_PERIOD_SECONDS,
                        exitDelay,
                        logFilePath)
                    .start();

            RecordedRequest request = retrieveRequest();
            assertNotNull(request);
            assertFalse(logHasErrors(logFilePath));
            assertTrue(request.getBodySize() > 0);

            // Wait for the app exit with some extra time.
            // The expectation is that agent doesn't prevent app from exiting.
            assertTrue(targetProcess.waitFor(exitDelay + 10, TimeUnit.SECONDS));
          } finally {
            if (targetProcess != null) {
              targetProcess.destroyForcibly();
            }
            targetProcess = null;
          }
        },
        testInfo,
        3);
  }

  private void testWithRetry(TestBody test, TestInfo testInfo, int retries) throws Exception {
    int cnt = retries;
    Throwable lastThrowable = null;
    while (cnt >= 0) {
      // clean the lastThrowable first
      lastThrowable = null;
      // clean the log file so the previous errors do not throw off the assertions
      Files.deleteIfExists(logFilePath);
      try {
        test.run();
        break;
      } catch (Throwable t) {
        lastThrowable = t;
        if (cnt > 0) {
          log.error("Test '{}' failed. Retrying.", testInfo.getDisplayName(), t);
        }
        // retry
      }
      cnt--;
    }
    if (lastThrowable != null) {
      throw new RuntimeException(
          "Failed '" + testInfo.getDisplayName() + "' after " + retries + " retries.",
          lastThrowable);
    }
  }

  private RecordedRequest retrieveRequest() throws Exception {
    return retrieveRequest(5 * REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
  }

  private RecordedRequest retrieveRequest(long timeout, TimeUnit timeUnit) throws Exception {
    long ts = System.nanoTime();
    RecordedRequest request = profilingServer.takeRequest(timeout, timeUnit);
    long dur = System.nanoTime() - ts;
    log.info(
        "Profiling request retrieved in {} seconds",
        TimeUnit.SECONDS.convert(dur, TimeUnit.NANOSECONDS));
    return request;
  }

  private void assertRecordingEvents(IItemCollection events) {
    IItemCollection scopeEvents = events.apply(ItemFilters.type("datadog.Scope"));

    assertTrue(scopeEvents.hasItems());
    IAttribute<IQuantity> cpuTimeAttr = Attribute.attr("cpuTime", "cpuTime", UnitLookup.TIMESPAN);

    // filter out scope events without CPU time data
    IItemCollection filteredScopeEvents =
        scopeEvents.apply(
            ItemFilters.more(cpuTimeAttr, UnitLookup.NANOSECOND.quantity(Long.MIN_VALUE)));
    // make sure there is at least one scope event with CPU time data
    assertTrue(filteredScopeEvents.hasItems());

    assertTrue(
        ((IQuantity)
                    filteredScopeEvents.getAggregate(Aggregators.min("datadog.Scope", cpuTimeAttr)))
                .longValue()
            >= 10_000L);

    // check exception events
    assertTrue(events.apply(ItemFilters.type("datadog.ExceptionSample")).hasItems());
    assertTrue(events.apply(ItemFilters.type("datadog.ExceptionCount")).hasItems());

    // check deadlock events
    assertTrue(events.apply(ItemFilters.type("datadog.Deadlock")).hasItems());
    assertTrue(events.apply(ItemFilters.type("datadog.DeadlockedThread")).hasItems());

    // check available processor events
    IItemCollection availableProcessorsEvents =
        events.apply(ItemFilters.type("datadog.AvailableProcessorCores"));
    assertTrue(availableProcessorsEvents.hasItems());
    IAttribute<IQuantity> cpuCountAttr =
        Attribute.attr("availableProcessorCores", "availableProcessorCores", UnitLookup.NUMBER);
    long val =
        ((IQuantity)
                availableProcessorsEvents.getAggregate(
                    Aggregators.min("datadog.AvailableProcessorCores", cpuCountAttr)))
            .longValue();
    assertEquals(Runtime.getRuntime().availableProcessors(), val);
  }

  private static String getStringParameter(String name, Multimap<String, Object> parameters) {
    return getParameter(name, String.class, parameters);
  }

  @SuppressWarnings("unchecked")
  private static <T> T getParameter(
      String name, Class<T> type, Multimap<String, Object> parameters) {
    List<?> vals = (List<?>) parameters.get(name);
    return (T) vals.get(0);
  }

  private ProcessBuilder createDefaultProcessBuilder(int jmxFetchDelay, Path logFilePath) {
    return createProcessBuilder(
        VALID_API_KEY,
        jmxFetchDelay,
        PROFILING_START_DELAY_SECONDS,
        PROFILING_UPLOAD_PERIOD_SECONDS,
        0,
        logFilePath);
  }

  private ProcessBuilder createProcessBuilder(
      String apiKey,
      int jmxFetchDelaySecs,
      int profilingStartDelaySecs,
      int profilingUploadPeriodSecs,
      int exitDelay,
      Path logFilePath) {
    return createProcessBuilder(
        profilingServer.getPort(),
        tracingServer.getPort(),
        apiKey,
        jmxFetchDelaySecs,
        profilingStartDelaySecs,
        profilingUploadPeriodSecs,
        exitDelay,
        logFilePath);
  }

  private static ProcessBuilder createProcessBuilder(
      int profilerPort,
      int tracerPort,
      String apiKey,
      int jmxFetchDelaySecs,
      int profilingStartDelaySecs,
      int profilingUploadPeriodSecs,
      int exitDelay,
      Path logFilePath) {
    String templateOverride =
        ProfilingIntegrationTest.class.getClassLoader().getResource("overrides.jfp").getFile();

    List<String> command =
        Arrays.asList(
            javaPath(),
            "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "512M"),
            "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"),
            "-javaagent:" + agentShadowJar(),
            "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
            "-Ddd.trace.agent.port=" + tracerPort,
            "-Ddd.service.name=smoke-test-java-app",
            "-Ddd.env=smoketest",
            "-Ddd.version=99",
            "-Ddd.profiling.enabled=true",
            "-DD.profiling.agentless=" + (apiKey != null),
            "-Ddd.profiling.start-delay=" + profilingStartDelaySecs,
            "-Ddd.profiling.upload.period=" + profilingUploadPeriodSecs,
            "-Ddd.profiling.url=http://localhost:" + profilerPort,
            "-Ddd.profiling.hotspots.enabled=true",
            "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug",
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+UnlockCommercialFeatures",
            "-XX:+FlightRecorder",
            "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride,
            "-Ddd.jmxfetch.start-delay=" + jmxFetchDelaySecs,
            "-jar",
            profilingShadowJar(),
            Integer.toString(exitDelay));
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.environment().put("DD_API_KEY", apiKey);

    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  private static String javaPath() {
    final String separator = System.getProperty("file.separator");
    return System.getProperty("java.home") + separator + "bin" + separator + "java";
  }

  private static String buildDirectory() {
    return System.getProperty("datadog.smoketest.builddir");
  }

  private static String agentShadowJar() {
    return System.getProperty("datadog.smoketest.agent.shadowJar.path");
  }

  private static String profilingShadowJar() {
    return System.getProperty("datadog.smoketest.profiling.shadowJar.path");
  }

  private static boolean checkLogLines(Path logFilePath, Predicate<String> checker)
      throws IOException {
    return Files.lines(logFilePath).anyMatch(checker);
  }

  private static boolean logHasErrors(Path logFilePath) throws IOException {
    boolean[] logHasErrors = new boolean[] {false};
    Files.lines(logFilePath)
        .forEach(
            it -> {
              if (it.contains("ERROR") || it.contains("ASSERTION FAILED")) {
                System.out.println(it);
                logHasErrors[0] = true;
              }
            });
    if (logHasErrors[0]) {
      System.out.println(
          "Test application log is containing errors. See full run logs in " + logFilePath);
    }
    return logHasErrors[0];
  }
}
