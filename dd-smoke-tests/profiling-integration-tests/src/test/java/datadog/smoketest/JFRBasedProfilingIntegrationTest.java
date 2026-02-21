package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.PLAIN_TEXT;

import com.datadog.profiling.testing.ProfilingTestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Multimap;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.trace.api.Pair;
import datadog.trace.api.config.ProfilingConfig;
import delight.fileupload.FileUpload;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.commons.fileupload.FileItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.item.Aggregators;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisabledIfSystemProperty(named = "java.vm.name", matches = ".*J9.*")
class JFRBasedProfilingIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(JFRBasedProfilingIntegrationTest.class);
  private static final Duration ONE_NANO = Duration.ofNanos(1);
  private static final int STACK_DEPTH_LIMIT = 8;

  @FunctionalInterface
  private interface TestBody {
    void run() throws Exception;
  }

  private static final String VALID_API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final String BOGUS_API_KEY = "bogus";
  private static final int PROFILING_START_DELAY_SECONDS = 1;
  private static final int PROFILING_UPLOAD_PERIOD_SECONDS = 5;

  private static final int PROFILING_UPLOAD_TIMEOUT_SECONDS = 1;
  private static final boolean ENDPOINT_COLLECTION_ENABLED = true; // default
  // Set the request timeout value to the sum of the initial delay and the upload period
  // multiplied by a safety margin
  private static final int SAFETY_MARGIN = 3;
  private static final int REQUEST_WAIT_TIMEOUT =
      (PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS) * SAFETY_MARGIN;

  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + JFRBasedProfilingIntegrationTest.class.getName());

  public static final IAttribute<IQuantity> LOCAL_ROOT_SPAN_ID =
      attr("localRootSpanId", "localRootSpanId", "localRootSpanId", NUMBER);
  public static final IAttribute<IQuantity> SPAN_ID = attr("spanId", "spanId", "spanId", NUMBER);

  public static final IAttribute<String> FOO = attr("foo", "", "", PLAIN_TEXT);
  public static final IAttribute<String> BAR = attr("bar", "", "", PLAIN_TEXT);

  public static final IAttribute<String> OPERATION =
      attr("_dd.trace.operation", "", "", PLAIN_TEXT);

  private MockWebServer profilingServer;
  private MockWebServer tracingServer;

  private Process targetProcess = null;
  private Path logFilePath = null;

  @BeforeAll
  static void setupAll() throws Exception {
    Files.createDirectories(LOG_FILE_BASE);
  }

  @BeforeEach
  void setup(final TestInfo testInfo) throws Exception {
    tracingServer = new MockWebServer();
    profilingServer = new MockWebServer();
    tracingServer.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(final RecordedRequest request) throws InterruptedException {
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

  private static Stream<Arguments> testArguments() {
    return Stream.of(
        Arguments.of(0, "on", "jfr"),
        Arguments.of(0, "on", "ddprof"),
        Arguments.of(0, "lz4", "jfr"),
        Arguments.of(0, "lz4", "ddprof"),
        Arguments.of(1, "on", "jfr"),
        Arguments.of(1, "on", "ddprof"),
        Arguments.of(1, "lz4", "jfr"),
        Arguments.of(1, "lz4", "ddprof"));
  }

  @ParameterizedTest(name = "Continuous recording [jmx delay: {0}, compression: {1}, mode: {2}]")
  @MethodSource("testArguments")
  public void testContinuousRecording_with_params(
      int jmxDelay, String compression, String mode, final TestInfo testInfo) throws Exception {
    Assumptions.assumeTrue("jfr".equals(mode) || OperatingSystem.isLinux());
    // Do not test compressions for Oracle JDK 8 - it will always be GZIP
    Assumptions.assumeTrue(!JavaVirtualMachine.isOracleJDK8() || "on".equals(mode));
    testWithRetry(
        () ->
            testContinuousRecording(
                jmxDelay, ENDPOINT_COLLECTION_ENABLED, "ddprof".equals(mode), compression),
        testInfo,
        5);
  }

  private void testContinuousRecording(
      final int jmxFetchDelay,
      final boolean endpointCollectionEnabled,
      final boolean asyncProfilerEnabled,
      final String withCompression)
      throws Exception {
    final ObjectMapper mapper = new ObjectMapper();
    try {
      targetProcess =
          createDefaultProcessBuilder(
                  jmxFetchDelay,
                  endpointCollectionEnabled,
                  asyncProfilerEnabled,
                  withCompression,
                  logFilePath)
              .start();

      Assumptions.assumeFalse(JavaVirtualMachine.isJ9());

      final RecordedRequest firstRequest = retrieveRequest();

      assertNotNull(firstRequest);
      assertEquals(profilingServer.getPort(), firstRequest.getRequestUrl().url().getPort());

      final List<FileItem> firstRequestMultiPartItems =
          FileUpload.parse(
              firstRequest.getBody().readByteArray(), firstRequest.getHeader("Content-Type"));

      FileItem rawEvent = firstRequestMultiPartItems.get(0);
      assertEquals("event", rawEvent.getFieldName());
      assertEquals("event.json", rawEvent.getName());
      assertEquals("application/json", rawEvent.getContentType());

      FileItem rawJfr = firstRequestMultiPartItems.get(1);
      assertEquals("main", rawJfr.getFieldName());
      assertEquals("main.jfr", rawJfr.getName());
      assertEquals("application/octet-stream", rawJfr.getContentType());

      // Event checks
      JsonNode event = mapper.readTree(rawEvent.getString());

      assertEquals("main.jfr", event.get("attachments").get(0).asText());
      assertEquals("java", event.get("family").asText());
      assertEquals("4", event.get("version").asText());
      final Instant firstStartTime = Instant.parse(event.get("start").asText());
      final Instant firstEndTime = Instant.parse(event.get("end").asText());
      assertNotNull(firstStartTime);
      assertNotNull(firstEndTime);

      final long duration = firstEndTime.toEpochMilli() - firstStartTime.toEpochMilli();
      long delta = duration - TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS);
      assertTrue(
          duration > TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS - 4),
          delta + "ms outside tolerance of upload period");
      assertTrue(
          duration < TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS + 4),
          delta + "ms outside tolerance of upload period");

      final Map<String, String> requestTags =
          ProfilingTestUtils.parseTags(
              Arrays.asList(event.get("tags_profiler").asText().split(",")));
      assertEquals("smoke-test-java-app", requestTags.get("service"));
      assertEquals("jvm", requestTags.get("language"));
      assertNotNull(requestTags.get("runtime-id"));
      assertEquals(InetAddress.getLocalHost().getHostName(), requestTags.get("host"));

      assertFalse(logHasErrors(logFilePath));
      InputStream eventStream = new ByteArrayInputStream(rawJfr.get());
      eventStream = decompressStream(withCompression, eventStream);
      IItemCollection events = JfrLoaderToolkit.loadEvents(eventStream);
      assertTrue(events.hasItems());
      Pair<Instant, Instant> rangeStartAndEnd = getRangeStartAndEnd(events);
      // This nano-second compensates for the added nano second in
      // ProfilingSystem.SnapshotRecording.snapshot()
      final Instant firstRangeStart = rangeStartAndEnd.getLeft().plus(ONE_NANO);
      final Instant firstRangeEnd = rangeStartAndEnd.getRight();
      assertTrue(
          firstStartTime.compareTo(firstRangeStart) <= 0,
          () ->
              "First range start "
                  + firstRangeStart
                  + " is before first start time "
                  + firstStartTime);

      final RecordedRequest nextRequest = retrieveRequest();
      assertNotNull(nextRequest);

      final List<FileItem> secondRequestMultiPartItems =
          FileUpload.parse(
              nextRequest.getBody().readByteArray(), nextRequest.getHeader("Content-Type"));

      rawEvent = secondRequestMultiPartItems.get(0);
      assertEquals("event", rawEvent.getFieldName());
      assertEquals("event.json", rawEvent.getName());
      assertEquals("application/json", rawEvent.getContentType());

      rawJfr = secondRequestMultiPartItems.get(1);
      assertEquals("main", rawJfr.getFieldName());
      assertEquals("main.jfr", rawJfr.getName());
      assertEquals("application/octet-stream", rawJfr.getContentType());

      // Event checks
      event = mapper.readTree(rawEvent.getString());

      assertEquals("main.jfr", event.get("attachments").get(0).asText());
      assertEquals("java", event.get("family").asText());
      assertEquals("4", event.get("version").asText());
      final Instant secondStartTime = Instant.parse(event.get("start").asText());
      final Instant secondEndTime = Instant.parse(event.get("end").asText());
      final long period = secondStartTime.toEpochMilli() - firstStartTime.toEpochMilli();
      final long upperLimit = TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS) * 2;

      assertTrue(
          period > 0 && period <= upperLimit,
          () -> "Upload period = " + period + "ms, expected (0, " + upperLimit + "]ms");

      eventStream = new ByteArrayInputStream(rawJfr.get());
      eventStream = decompressStream(withCompression, eventStream);
      events = JfrLoaderToolkit.loadEvents(eventStream);
      assertTrue(events.hasItems());
      verifyDatadogEventsNotCorrupt(events);
      rangeStartAndEnd = getRangeStartAndEnd(events);
      // This nano-second compensates for the added nano second in
      // ProfilingSystem.SnapshotRecording.snapshot()
      final Instant secondRangeStart = rangeStartAndEnd.getLeft().plus(ONE_NANO);
      final Instant secondRangeEnd = rangeStartAndEnd.getRight();
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
        assertRecordingEvents(events, endpointCollectionEnabled, asyncProfilerEnabled);
      }
    } finally {
      if (targetProcess != null) {
        targetProcess.destroyForcibly();
      }
      targetProcess = null;
    }
  }

  private static InputStream decompressStream(String withCompression, InputStream eventStream) {
    if (!JavaVirtualMachine.isOracleJDK8()) {
      if ("zstd".equals(withCompression) || "on".equals(withCompression)) {
        eventStream = new ZstdInputStream(eventStream);
      } else {
        // nothing; jmc already handles lz4 and gzip
      }
    } else {
      // Oracle JDK 8 JFR writes files in GZIP format; jmc already can handle that
    }

    return eventStream;
  }

  private static void verifyJdkEventsDisabled(IItemCollection events) {
    assertFalse(events.apply(ItemFilters.type("jdk.ExecutionSample")).hasItems());
    assertFalse(events.apply(ItemFilters.type("jdk.ThreadPark")).hasItems());
  }

  private static void verifyJdkEvents(IItemCollection events) {
    String cpuSampleType = "jdk.ExecutionSample";
    if (JavaVirtualMachine.isJavaVersionAtLeast(25) && OperatingSystem.isLinux()) {
      // for Java 25+ we are defaulting to 'jdk.CPUTimeSample' on Linux
      cpuSampleType = "jdk.CPUTimeSample";
    }
    assertTrue(events.apply(ItemFilters.type(cpuSampleType)).hasItems());
  }

  private static void verifyDatadogEventsNotCorrupt(IItemCollection events) {
    // if we emit any of these events during the test they mustn't have corrupted context
    for (String eventName :
        new String[] {
          "datadog.ExecutionSample",
          "datadog.MethodSample",
          "datadog.ObjectAllocationInNewTLAB",
          "datadog.ObjectAllocationOutsideTLAB",
          "datadog.HeapLiveObject",
          "datadog.JavaMonitorEnter"
        }) {
      for (IItemIterable event : events.apply(ItemFilters.type(eventName))) {
        IMemberAccessor<IQuantity, IItem> rootSpanIdAccessor =
            LOCAL_ROOT_SPAN_ID.getAccessor(event.getType());
        IMemberAccessor<IQuantity, IItem> spanIdAccessor = SPAN_ID.getAccessor(event.getType());
        for (IItem sample : event) {
          long rootSpanId = rootSpanIdAccessor.getMember(sample).longValue();
          assertTrue(rootSpanId >= 0, "rootSpanId must not be negative");
          long spanId = spanIdAccessor.getMember(sample).longValue();
          assertTrue(spanId >= 0, "spanId must not be negative");
        }
      }
    }
  }

  private Instant convertFromQuantity(final IQuantity instant) {
    Instant converted = null;
    try {
      final IQuantity rangeS = instant.in(UnitLookup.EPOCH_S);
      final long es = rangeS.longValue();
      final long ns =
          instant.longValueIn(UnitLookup.EPOCH_NS) - rangeS.longValueIn(UnitLookup.EPOCH_NS);
      converted = Instant.ofEpochSecond(es, ns);
    } catch (final QuantityConversionException ignore) {
    }
    return converted;
  }

  private Pair<Instant, Instant> getRangeStartAndEnd(final IItemCollection events) {
    return events.getUnfilteredTimeRanges().stream()
        .map(
            range -> {
              final Instant convertedStart = convertFromQuantity(range.getStart());
              final Instant convertedEnd = convertFromQuantity(range.getEnd());
              return Pair.of(convertedStart, convertedEnd);
            })
        .reduce(
            Pair.of(null, null),
            (send, newSend) -> {
              Instant start = send.getLeft();
              Instant end = send.getRight();
              final Instant newStart = newSend.getLeft();
              final Instant newEnd = newSend.getRight();
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
  void testBogusApiKey(final TestInfo testInfo) throws Exception {
    testWithRetry(
        () -> {
          final int exitDelay =
              PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS * 2 + 1;

          try {
            targetProcess =
                createProcessBuilder(
                        BOGUS_API_KEY,
                        0,
                        PROFILING_START_DELAY_SECONDS,
                        PROFILING_UPLOAD_PERIOD_SECONDS,
                        ENDPOINT_COLLECTION_ENABLED,
                        true,
                        "off",
                        exitDelay,
                        logFilePath)
                    .start();

            /* API key of an incorrect format will cause profiling to get disabled.
              This means no upload requests will be made. We are going to check the log file for
              the presence of a specific message. For this we need to wait for a particular message indicating
              that the profiling system is initializing and then assert for the presence of the expected message
              caused by the API key format error.
            */
            final long ts = System.nanoTime();
            while (!checkLogLines(
                logFilePath, line -> line.contains("Initializing profiler context integration"))) {
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
  @DisplayName("Test wallclock profiling without tracing")
  public void testWallclockProfilingWithoutTracing(final TestInfo testInfo) throws Exception {
    Assumptions.assumeTrue(OperatingSystem.isLinux());
    // TODO: Exclude the test on Oracle JDK 8 - the JMC parser in the test runner
    //       is having troubles reading the generated JFR file; however, when downloaded
    //       and opened locally, JMC will read it just fine.
    //       We will need to investigate the root cause, but now we need to unblock the master
    // builds
    Assumptions.assumeFalse(JavaVirtualMachine.isOracleJDK8());
    testWithRetry(
        () -> {
          try {
            targetProcess =
                createProcessBuilder(
                        profilingServer.getPort(),
                        tracingServer.getPort(),
                        VALID_API_KEY,
                        0,
                        PROFILING_START_DELAY_SECONDS,
                        PROFILING_UPLOAD_PERIOD_SECONDS,
                        false,
                        true,
                        "on",
                        0,
                        logFilePath,
                        false)
                    .start();

            Assumptions.assumeFalse(JavaVirtualMachine.isJ9());

            final RecordedRequest request = retrieveRequest();
            assertNotNull(request);

            final List<FileItem> items =
                FileUpload.parse(
                    request.getBody().readByteArray(), request.getHeader("Content-Type"));

            FileItem rawJfr = items.get(1);
            assertEquals("main.jfr", rawJfr.getName());

            assertFalse(logHasErrors(logFilePath));
            InputStream eventStream = new ByteArrayInputStream(rawJfr.get());
            eventStream = decompressStream("on", eventStream);
            IItemCollection events = JfrLoaderToolkit.loadEvents(eventStream);
            assertTrue(events.hasItems());

            IItemCollection wallclockSamples =
                events.apply(ItemFilters.type("datadog.MethodSample"));
            assertTrue(
                wallclockSamples.hasItems(), "Expected wallclock samples when tracing is disabled");

            // Verify span context is not present
            for (IItemIterable event : wallclockSamples) {
              IMemberAccessor<IQuantity, IItem> rootSpanIdAccessor =
                  LOCAL_ROOT_SPAN_ID.getAccessor(event.getType());
              IMemberAccessor<IQuantity, IItem> spanIdAccessor =
                  SPAN_ID.getAccessor(event.getType());
              for (IItem sample : event) {
                assertEquals(
                    0,
                    rootSpanIdAccessor.getMember(sample).longValue(),
                    "rootSpanId should be 0 when tracing is disabled");
                assertEquals(
                    0,
                    spanIdAccessor.getMember(sample).longValue(),
                    "spanId should be 0 when tracing is disabled");
              }
            }
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

  @Test
  @DisplayName("Test shutdown")
  @Disabled("https://github.com/DataDog/dd-trace-java/pull/5213")
  void testShutdown(final TestInfo testInfo) throws Exception {
    testWithRetry(
        () -> {
          final int duration =
              PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS * 4 + 1;
          try {
            targetProcess =
                createProcessBuilder(
                        VALID_API_KEY,
                        0,
                        PROFILING_START_DELAY_SECONDS,
                        PROFILING_UPLOAD_PERIOD_SECONDS,
                        ENDPOINT_COLLECTION_ENABLED,
                        true,
                        "off",
                        duration,
                        logFilePath)
                    .start();

            final RecordedRequest request = retrieveRequest();
            assertNotNull(request);
            assertFalse(logHasErrors(logFilePath));
            assertTrue(request.getBodySize() > 0);

            // Wait for the app exit with some extra time to accommodate profile upload on shutdown.
            // The expectation is that agent doesn't prevent app from exiting.
            assertTrue(
                targetProcess.waitFor(
                    duration + PROFILING_UPLOAD_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS));
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

  private void testWithRetry(final TestBody test, final TestInfo testInfo, final int retries)
      throws Exception {
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
      } catch (final Throwable t) {
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

  private RecordedRequest retrieveRequest(final long timeout, final TimeUnit timeUnit)
      throws Exception {
    final long ts = System.nanoTime();
    final RecordedRequest request = profilingServer.takeRequest(timeout, timeUnit);
    final long dur = System.nanoTime() - ts;
    log.info(
        "Profiling request retrieved in {} seconds",
        TimeUnit.SECONDS.convert(dur, TimeUnit.NANOSECONDS));
    return request;
  }

  private void assertRecordingEvents(
      final IItemCollection events,
      final boolean expectEndpointEvents,
      final boolean asyncProfilerEnabled) {
    // Process events should not be collected
    assertFalse(
        events.apply(ItemFilters.type("jdk.SystemProcess")).hasItems(),
        "jdk.SystemProcess events should not be collected");

    if (expectEndpointEvents) {
      // Check endpoint events
      final IItemCollection endpointEvents = events.apply(ItemFilters.type("datadog.Endpoint"));
      assertEquals(expectEndpointEvents, endpointEvents.hasItems());
      if (asyncProfilerEnabled) {
        Set<Long> rootSpanIds = new HashSet<>();
        Set<String> operations = new HashSet<>();
        Set<String> values = new HashSet<>();
        processExecutionSamples(events, rootSpanIds, operations, values);
        int matches = 0;
        for (IItemIterable event : endpointEvents) {
          IMemberAccessor<IQuantity, IItem> rootSpanIdAccessor =
              LOCAL_ROOT_SPAN_ID.getAccessor(event.getType());
          for (IItem item : event) {
            long rootSpanId = rootSpanIdAccessor.getMember(item).longValue();
            matches += rootSpanIds.contains(rootSpanId) ? 1 : 0;
          }
        }
        // we expect a rough correspondence between these events
        assertTrue(matches > 0);
        assertFalse(values.isEmpty());
        for (String value : values) {
          assertTrue(value.startsWith("context"));
        }
      }
    }
    assertEquals(asyncProfilerEnabled, hasAuxiliaryDdprof(events));
    verifyStackDepthSetting(events, asyncProfilerEnabled);
    if (asyncProfilerEnabled) {
      verifyJdkEventsDisabled(events);
      verifyDatadogEventsNotCorrupt(events);
      assertEquals(
          JavaVirtualMachine.isJavaVersionAtLeast(11),
          events.apply(ItemFilters.type("datadog.ObjectSample")).hasItems());
      // TODO ddprof (async) profiler seems to be having some issues with stack depth limit and
      // native frames
    } else {
      verifyJdkEvents(events);
      // make sure the stack depth limit is respected
      for (IItemIterable lane : events.apply(ItemFilters.type(JdkTypeIDs.EXECUTION_SAMPLE))) {
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor =
            JfrAttributes.EVENT_STACKTRACE.getAccessor(lane.getType());
        for (IItem item : lane) {
          IMCStackTrace stackTrace = stackTraceAccessor.getMember(item);
          assertNotNull(stackTrace);
          assertTrue(stackTrace.getFrames().size() <= STACK_DEPTH_LIMIT);
        }
      }
    }

    // check exception events
    assertTrue(events.apply(ItemFilters.type("datadog.ExceptionSample")).hasItems());
    assertTrue(events.apply(ItemFilters.type("datadog.ExceptionCount")).hasItems());

    // check deadlock events
    assertTrue(events.apply(ItemFilters.type("datadog.Deadlock")).hasItems());
    assertTrue(events.apply(ItemFilters.type("datadog.DeadlockedThread")).hasItems());

    // check available processor events
    final IItemCollection availableProcessorsEvents =
        events.apply(ItemFilters.type("datadog.AvailableProcessorCores"));
    assertTrue(availableProcessorsEvents.hasItems());
    final IAttribute<IQuantity> cpuCountAttr =
        attr("availableProcessorCores", "availableProcessorCores", NUMBER);
    final long val =
        ((IQuantity)
                availableProcessorsEvents.getAggregate(
                    Aggregators.min("datadog.AvailableProcessorCores", cpuCountAttr)))
            .longValue();
    assertEquals(Runtime.getRuntime().availableProcessors(), val);

    assertTrue(events.apply(ItemFilters.type("datadog.ProfilerSetting")).hasItems());
    //     FIXME - for some reason the events are disabled by JFR despite being explicitly enabled
    //    assertTrue(events.apply(ItemFilters.type("datadog.QueueTime")).hasItems());
  }

  private static void verifyStackDepthSetting(
      IItemCollection events, boolean asyncProfilerEnabled) {
    assertTrue(
        events
            .apply(
                ItemFilters.and(
                    ItemFilters.type("datadog.ProfilerSetting"),
                    ItemFilters.equals(
                        JdkAttributes.REC_SETTING_NAME,
                        (asyncProfilerEnabled ? "ddprof" : "JFR") + " Stack Depth"),
                    ItemFilters.equals(
                        JdkAttributes.REC_SETTING_VALUE, String.valueOf(STACK_DEPTH_LIMIT))))
            .hasItems());
  }

  private static boolean hasAuxiliaryDdprof(IItemCollection events) {
    events =
        events.apply(
            ItemFilters.and(
                ItemFilters.type("datadog.ProfilerSetting"),
                ItemFilters.equals(JdkAttributes.REC_SETTING_NAME, "Auxiliary Profiler")));
    if (!events.hasItems()) {
      return false;
    }
    for (IItemIterable event : events) {
      IMemberAccessor<String, IItem> valueAccessor =
          JdkAttributes.REC_SETTING_VALUE.getAccessor(event.getType());
      for (IItem item : event) {
        String value = valueAccessor.getMember(item);
        if ("ddprof".equals(value)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void processExecutionSamples(
      IItemCollection events, Set<Long> rootSpanIds, Set<String> operations, Set<String> values) {
    IItemCollection executionSamples = events.apply(ItemFilters.type("datadog.ExecutionSample"));
    for (IItemIterable executionSampleEvents : executionSamples) {
      IMemberAccessor<IQuantity, IItem> rootSpanIdAccessor =
          LOCAL_ROOT_SPAN_ID.getAccessor(executionSampleEvents.getType());
      IMemberAccessor<String, IItem> fooAccessor = FOO.getAccessor(executionSampleEvents.getType());
      IMemberAccessor<String, IItem> barAccessor = BAR.getAccessor(executionSampleEvents.getType());
      IMemberAccessor<String, IItem> operationAccessor =
          OPERATION.getAccessor(executionSampleEvents.getType());
      for (IItem executionSample : executionSampleEvents) {
        long rootSpanId = rootSpanIdAccessor.getMember(executionSample).longValue();
        rootSpanIds.add(rootSpanId);
        if (rootSpanId != 0) {
          operations.add(operationAccessor.getMember(executionSample));
        }
        String foo = fooAccessor.getMember(executionSample);
        if (foo != null) {
          values.add(foo);
        }
        assertNull(barAccessor.getMember(executionSample));
      }
    }
    assertEquals(1, operations.size(), "wrong number of operation names");
    assertEquals("trace.annotation", operations.iterator().next(), "wrong operation names");
  }

  private static <T> T getParameter(
      final String name, final Class<T> type, final Multimap<String, Object> parameters) {
    final List<?> vals = (List<?>) parameters.get(name);
    return (T) vals.get(0);
  }

  private ProcessBuilder createDefaultProcessBuilder(
      final int jmxFetchDelay,
      final boolean endpointCollectionEnabled,
      final boolean asyncProfilerEnabled,
      final String withCompression,
      final Path logFilePath) {
    return createProcessBuilder(
        VALID_API_KEY,
        jmxFetchDelay,
        PROFILING_START_DELAY_SECONDS,
        PROFILING_UPLOAD_PERIOD_SECONDS,
        endpointCollectionEnabled,
        asyncProfilerEnabled,
        withCompression,
        0,
        logFilePath);
  }

  private ProcessBuilder createProcessBuilder(
      final String apiKey,
      final int jmxFetchDelaySecs,
      final int profilingStartDelaySecs,
      final int profilingUploadPeriodSecs,
      final boolean endpointCollectionEnabled,
      final boolean asyncProfilerEnabled,
      final String withCompression,
      final int exitDelay,
      final Path logFilePath) {
    return createProcessBuilder(
        profilingServer.getPort(),
        tracingServer.getPort(),
        apiKey,
        jmxFetchDelaySecs,
        profilingStartDelaySecs,
        profilingUploadPeriodSecs,
        endpointCollectionEnabled,
        asyncProfilerEnabled,
        withCompression,
        exitDelay,
        logFilePath,
        true);
  }

  private static ProcessBuilder createProcessBuilder(
      final int profilerPort,
      final int tracerPort,
      final String apiKey,
      final int jmxFetchDelaySecs,
      final int profilingStartDelaySecs,
      final int profilingUploadPeriodSecs,
      final boolean endpointCollectionEnabled,
      final boolean asyncProfilerEnabled,
      final String withCompression,
      final int exitDelay,
      final Path logFilePath,
      final boolean tracingEnabled,
      final String... extraProperties) {
    final String templateOverride =
        JFRBasedProfilingIntegrationTest.class
            .getClassLoader()
            .getResource("overrides.jfp")
            .getFile();

    final List<String> command = new java.util.ArrayList<>();
    command.add(javaPath());
    command.add("-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "1024M"));
    command.add("-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M"));
    command.add("-javaagent:" + agentShadowJar());
    command.add("-XX:ErrorFile=/tmp/hs_err_pid%p.log");
    command.add("-Ddd.trace.agent.port=" + tracerPort);
    command.add("-Ddd.service.name=smoke-test-java-app");
    command.add("-Ddd.env=smoketest");
    command.add("-Ddd.version=99");
    command.add("-Ddd.trace.enabled=" + tracingEnabled);
    command.add("-Ddd.profiling.enabled=true");
    command.add("-Ddd.profiling.stackdepth=" + STACK_DEPTH_LIMIT);
    command.add("-Ddd.profiling.ddprof.enabled=" + asyncProfilerEnabled);
    command.add("-Ddd.profiling.ddprof.alloc.enabled=" + asyncProfilerEnabled);
    if (!tracingEnabled && asyncProfilerEnabled) {
      command.add("-Ddd.profiling.ddprof.wall.enabled=true");
    }
    command.add("-Ddd.profiling.agentless=" + (apiKey != null));
    command.add("-Ddd.profiling.start-delay=" + profilingStartDelaySecs);
    command.add("-Ddd.profiling.upload.period=" + profilingUploadPeriodSecs);
    command.add("-Ddd.profiling.url=http://localhost:" + profilerPort);
    command.add("-Ddd.profiling.hotspots.enabled=true");
    command.add("-Ddd.profiling.endpoint.collection.enabled=" + endpointCollectionEnabled);
    command.add("-Ddd.profiling.upload.timeout=" + PROFILING_UPLOAD_TIMEOUT_SECONDS);
    command.add("-Ddd.profiling.debug.dump_path=/tmp/dd-profiler");
    if (tracingEnabled) {
      command.add("-Ddd.profiling.queueing.time.enabled=true");
      command.add("-Ddd.profiling.queueing.time.threshold.millis=0");
      command.add("-Ddd.profiling.context.attributes=foo,bar");
    }
    command.add("-Ddd.profiling.debug.upload.compression=" + withCompression);
    for (String extra : extraProperties) {
      command.add(extra);
    }
    command.add("-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug");
    command.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=debug");
    command.add("-XX:+IgnoreUnrecognizedVMOptions");
    command.add("-XX:+UnlockCommercialFeatures");
    command.add("-XX:+FlightRecorder");
    command.add(
        "-Ddd." + ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE + "=" + templateOverride);
    command.add("-Ddd.jmxfetch.start-delay=" + jmxFetchDelaySecs);
    command.add("-jar");
    command.add(profilingShadowJar());
    command.add(Integer.toString(exitDelay));
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(buildDirectory()));

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    processBuilder.environment().put("DD_API_KEY", apiKey);

    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFilePath.toFile()));
    return processBuilder;
  }

  private static String javaPath() {
    return Paths.get(SystemProperties.getOrDefault("java.home", ""), "bin", "java").toString();
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

  private static boolean checkLogLines(final Path logFilePath, final Predicate<String> checker)
      throws IOException {
    return Files.lines(logFilePath).anyMatch(checker);
  }

  private static boolean logHasErrors(final Path logFilePath) throws IOException {
    final boolean[] logHasErrors = new boolean[] {false};
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

  public static boolean isJavaVersionAtLeast24() {
    return JavaVirtualMachine.isJavaVersionAtLeast(24);
  }

  @Test
  @DisplayName("Test JFR scrubbing")
  void testJfrScrubbing(final TestInfo testInfo) throws Exception {
    testWithRetry(
        () -> {
          try {
            targetProcess =
                createProcessBuilder(
                        profilingServer.getPort(),
                        tracingServer.getPort(),
                        VALID_API_KEY,
                        0,
                        PROFILING_START_DELAY_SECONDS,
                        PROFILING_UPLOAD_PERIOD_SECONDS,
                        ENDPOINT_COLLECTION_ENABLED,
                        true,
                        "on",
                        0,
                        logFilePath,
                        true,
                        "-Ddd.profiling.scrub.enabled=true")
                    .start();

            Assumptions.assumeFalse(JavaVirtualMachine.isJ9());

            final RecordedRequest request = retrieveRequest();
            assertNotNull(request);

            final List<FileItem> items =
                FileUpload.parse(
                    request.getBody().readByteArray(), request.getHeader("Content-Type"));

            FileItem rawJfr =
                items.stream()
                    .filter(i -> "main.jfr".equals(i.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("main.jfr not found in upload"));

            assertFalse(logHasErrors(logFilePath));
            InputStream eventStream = new ByteArrayInputStream(rawJfr.get());
            eventStream = decompressStream("on", eventStream);
            IItemCollection events = JfrLoaderToolkit.loadEvents(eventStream);
            assertTrue(events.hasItems());

            // Verify that system properties are scrubbed
            IItemCollection systemPropertyEvents =
                events.apply(ItemFilters.type(JdkTypeIDs.SYSTEM_PROPERTIES));
            assertTrue(
                systemPropertyEvents.hasItems(),
                "Expected jdk.InitialSystemProperty events in recording");
            {
              IAttribute<String> valueAttr = attr("value", "value", "value", PLAIN_TEXT);
              for (IItemIterable event : systemPropertyEvents) {
                IMemberAccessor<String, IItem> valueAccessor =
                    valueAttr.getAccessor(event.getType());
                for (IItem item : event) {
                  String value = valueAccessor.getMember(item);
                  if (value != null && !value.isEmpty()) {
                    // Scrubbed values should contain only 'x' characters
                    assertTrue(
                        value.chars().allMatch(c -> c == 'x'),
                        "System property value should be scrubbed: " + value);
                  }
                }
              }
            }

            // Verify that JVM arguments are scrubbed
            IItemCollection jvmInfoEvents = events.apply(ItemFilters.type("jdk.JVMInformation"));
            assertTrue(jvmInfoEvents.hasItems(), "Expected jdk.JVMInformation events in recording");
            {
              IAttribute<String> jvmArgsAttr =
                  attr("jvmArguments", "jvmArguments", "jvmArguments", PLAIN_TEXT);
              for (IItemIterable event : jvmInfoEvents) {
                IMemberAccessor<String, IItem> jvmArgsAccessor =
                    jvmArgsAttr.getAccessor(event.getType());
                for (IItem item : event) {
                  String jvmArgs = jvmArgsAccessor.getMember(item);
                  if (jvmArgs != null && !jvmArgs.isEmpty()) {
                    // Scrubbed values should contain only 'x' characters
                    assertTrue(
                        jvmArgs.chars().allMatch(c -> c == 'x'),
                        "JVM arguments should be scrubbed: " + jvmArgs);
                  }
                }
              }
            }
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
}
