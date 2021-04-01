package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.testing.ProfilingTestUtils;
import com.google.common.collect.Multimap;
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
import java.util.function.Function;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmc.common.item.Aggregators;
import org.openjdk.jmc.common.item.Attribute;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProfilingIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(ProfilingIntegrationTest.class);

  private static final String VALID_API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final String BOGUS_API_KEY = "bogus";
  private static final int PROFILING_START_DELAY_SECONDS = 1;
  private static final int PROFILING_UPLOAD_PERIOD_SECONDS = 3;
  // Set the request timeout value to the sum of the initial delay and the upload period
  // multiplied by a safety margin
  private static final int SAFETY_MARGIN = 10;
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

  @ParameterizedTest(name = "testContinuousRecording_{0}_sec")
  @ValueSource(ints = {0, 1})
  void testContinuousRecording(int jmxFetchDelay, TestInfo testInfo) throws Exception {
    targetProcess = createDefaultProcessBuilder(jmxFetchDelay, logFilePath).start();

    RecordedRequest firstRequest = retrieveRequest();

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

    assertFalse(logHasErrors(logFilePath, it -> false));
    IItemCollection events = JfrLoaderToolkit.loadEvents(new ByteArrayInputStream(byteData));
    assertTrue(events.hasItems());

    RecordedRequest nextRequest = retrieveRequest();
    assertNotNull(nextRequest);

    Multimap<String, Object> nextRequestParameters =
        ProfilingTestUtils.parseProfilingRequestParameters(nextRequest);
    Instant secondStartTime =
        Instant.parse(getStringParameter("recording-start", nextRequestParameters));
    long period = secondStartTime.toEpochMilli() - firstStartTime.toEpochMilli();
    assertTrue(period > TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS - 2));
    assertTrue(period < TimeUnit.SECONDS.toMillis(PROFILING_UPLOAD_PERIOD_SECONDS + 2));

    byteData = getParameter("chunk-data", byte[].class, firstRequestParameters);
    assertNotNull(byteData);
    events = JfrLoaderToolkit.loadEvents(new ByteArrayInputStream(byteData));
    assertTrue(events.hasItems());

    // Only non-Oracle JDK 8+ JVMs support custom DD events
    if (!System.getProperty("java.vendor").contains("Oracle")
        || !System.getProperty("java.version").contains("1.8")) {
      assertRecordingEvents(events);
    }
  }

  @Test
  @DisplayName("testBogusApiKey")
  void testBogusApiKey() throws Exception {
    int exitDelay = PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS * 2 + 1;

    targetProcess =
        createProcessBuilder(
                BOGUS_API_KEY,
                0,
                PROFILING_START_DELAY_SECONDS,
                PROFILING_UPLOAD_PERIOD_SECONDS,
                exitDelay,
                logFilePath)
            .start();

    RecordedRequest request = retrieveRequest();
    assertFalse(logHasErrors(logFilePath, it -> false));
  }

  @Test
  @DisplayName("testShutdown")
  void testShutdown() throws Exception {
    int exitDelay = PROFILING_START_DELAY_SECONDS + PROFILING_UPLOAD_PERIOD_SECONDS * 2 + 1;
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
    assertFalse(logHasErrors(logFilePath, it -> false));
    assertTrue(request.getBodySize() > 0);

    // Wait for the app exit with some extra time.
    // The expectation is that agent doesn't prevent app from exiting.
    assertTrue(targetProcess.waitFor(exitDelay + 10, TimeUnit.SECONDS));
  }

  private RecordedRequest retrieveRequest() throws Exception {
    long ts = System.nanoTime();
    RecordedRequest request = profilingServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
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
            "-Ddd.profiling.start-delay=" + profilingStartDelaySecs,
            "-Ddd.profiling.upload.period=" + profilingUploadPeriodSecs,
            "-Ddd.profiling.url=http://localhost:" + profilerPort,
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

  private static boolean logHasErrors(Path logFilePath, Function<String, Boolean> checker)
      throws IOException {
    boolean[] logHasErrors = new boolean[] {false};
    Files.lines(logFilePath)
        .forEach(
            it -> {
              if (it.contains("ERROR") || it.contains("ASSERTION FAILED")) {
                System.out.println(it);
                logHasErrors[0] = true;
              }
              logHasErrors[0] |= checker.apply(it);
            });
    if (logHasErrors[0]) {
      System.out.println(
          "Test application log is containing errors. See full run logs in " + logFilePath);
    }
    return logHasErrors[0];
  }
}
