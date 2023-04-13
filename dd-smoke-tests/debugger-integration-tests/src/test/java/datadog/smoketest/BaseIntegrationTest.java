package datadog.smoketest;

import static datadog.smoketest.ProcessBuilderHelper.buildDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.CapturedContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseIntegrationTest {
  protected static final Logger LOG = LoggerFactory.getLogger(BaseIntegrationTest.class);
  protected static final String PROBE_URL_PATH = "/v0.7/config";
  protected static final String SNAPSHOT_URL_PATH = "/debugger/v1/input";
  protected static final int REQUEST_WAIT_TIMEOUT = 10;
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(), "reports", "testProcess." + DebuggerIntegrationTest.class.getName());
  private static final String INFO_CONTENT =
      "{\"endpoints\": [\"v0.4/traces\", \"debugger/v1/input\", \"v0.7/config\"]}";
  private static final MockResponse AGENT_INFO_RESPONSE =
      new MockResponse().setResponseCode(200).setBody(INFO_CONTENT);
  private static final MockResponse TELEMETRY_RESPONSE = new MockResponse().setResponseCode(202);
  private static final MockResponse EMPTY_200_RESPONSE = new MockResponse().setResponseCode(200);

  protected MockWebServer datadogAgentServer;
  private MockDispatcher probeMockDispatcher;

  private HttpUrl probeUrl;
  private HttpUrl snapshotUrl;
  protected Path logFilePath;
  protected Process targetProcess;
  private Configuration currentConfiguration;
  private boolean configProvided;
  protected final Object configLock = new Object();

  @BeforeAll
  static void setupAll() throws Exception {
    Files.createDirectories(LOG_FILE_BASE);
  }

  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    datadogAgentServer = new MockWebServer();
    probeMockDispatcher = new MockDispatcher();
    probeMockDispatcher.setDispatcher(this::datadogAgentDispatch);
    datadogAgentServer.setDispatcher(probeMockDispatcher);
    probeUrl = datadogAgentServer.url(PROBE_URL_PATH);
    LOG.info("DatadogAgentServer on {}", datadogAgentServer.getPort());
    snapshotUrl = datadogAgentServer.url(SNAPSHOT_URL_PATH);
    logFilePath = LOG_FILE_BASE.resolve(testInfo.getDisplayName() + ".log");
  }

  @AfterEach
  void teardown() throws Exception {
    if (targetProcess != null) {
      targetProcess.destroyForcibly();
    }
    datadogAgentServer.shutdown();
  }

  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=false");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  protected abstract String getAppClass();

  protected abstract String getAppId();

  protected List<String> getDebuggerCommandParams() {
    return new ArrayList<>(
        Arrays.asList(
            "-Ddd.service.name=" + getAppId(),
            "-Ddd.profiling.enabled=false",
            "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
            "-Ddd.jmxfetch.start-delay=0",
            "-Ddd.jmxfetch.enabled=false",
            "-Ddd.dynamic.instrumentation.enabled=true",
            // "-Ddd.remote_config.enabled=true", // default
            "-Ddd.remote_config.poll_interval.seconds=1",
            /*"-Ddd.remote_config.integrity_check.enabled=false",
            "-Ddd.dynamic.instrumentation.probe.url=http://localhost:"
                + probeServer.getPort()
                + PROBE_URL_PATH,
            "-Ddd.dynamic.instrumentation.snapshot.url=http://localhost:"
                + snapshotServer.getPort()
                + SNAPSHOT_URL_PATH,*/
            "-Ddd.trace.agent.url=http://localhost:" + datadogAgentServer.getPort(),
            // to verify each snapshot upload one by one
            "-Ddd.dynamic.instrumentation.upload.batch.size=1",
            // flush uploads every 100ms to have quick tests
            "-Ddd.dynamic.instrumentation.upload.flush.interval=100"));
  }

  protected RecordedRequest retrieveSnapshotRequest() throws Exception {
    long ts = System.nanoTime();
    RecordedRequest request;

    do {
      request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
    } while (request != null
        && (!request.getPath().startsWith(SNAPSHOT_URL_PATH)
            || request.getBody().indexOf(ByteString.encodeUtf8("diagnostics")) > -1));
    long dur = System.nanoTime() - ts;
    LOG.info(
        "request retrieved in {} seconds", TimeUnit.SECONDS.convert(dur, TimeUnit.NANOSECONDS));
    return request;
  }

  protected ProbeStatus retrieveProbeStatusRequest() throws Exception {
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
    } while (request != null && !request.getPath().startsWith(SNAPSHOT_URL_PATH));
    assertNotNull(request);
    JsonAdapter<List<ProbeStatus>> adapter =
        MoshiHelper.createMoshiProbeStatus()
            .adapter(Types.newParameterizedType(List.class, ProbeStatus.class));
    String bodyStr = request.getBody().readUtf8();
    LOG.info("got snapshot: {}", bodyStr);
    List<ProbeStatus> probeStatuses = adapter.fromJson(bodyStr);
    assertEquals(1, probeStatuses.size());
    return probeStatuses.get(0);
  }

  private MockResponse datadogAgentDispatch(RecordedRequest request) {
    LOG.info("datadogAgentDispatch request path: {}", request.getPath());
    if (request.getPath().equals("/info")) {
      return AGENT_INFO_RESPONSE;
    }
    if (request.getPath().equals("/telemetry/proxy/api/v2/apmtelemetry")) {
      // Ack every telemetry request. This is needed if telemetry is enabled in the tests.
      return TELEMETRY_RESPONSE;
    }
    if (request.getPath().startsWith(SNAPSHOT_URL_PATH)) {
      return new MockResponse().setResponseCode(200);
    }
    if (request.getPath().equals("/v0.7/config")) {
      Configuration configuration;
      synchronized (configLock) {
        configuration = getCurrentConfiguration();
        configProvided = true;
        configLock.notifyAll();
      }
      if (configuration == null) {
        configuration = createConfig(Collections.emptyList());
      }
      try {
        JsonAdapter<Configuration> adapter =
            MoshiConfigTestHelper.createMoshiConfig().adapter(Configuration.class);
        String json = adapter.toJson(configuration);
        System.out.println("Sending json config: " + json);
        String remoteConfigJson = RemoteConfigHelper.encode(json, UUID.randomUUID().toString());
        return new MockResponse().setResponseCode(200).setBody(remoteConfigJson);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return EMPTY_200_RESPONSE;
  }

  private Configuration getCurrentConfiguration() {
    synchronized (configLock) {
      return currentConfiguration;
    }
  }

  protected boolean isConfigProvided() {
    synchronized (configLock) {
      return configProvided;
    }
  }

  protected JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> createAdapterForSnapshot() {
    return MoshiSnapshotTestHelper.createMoshiSnapshot()
        .adapter(
            Types.newParameterizedType(List.class, JsonSnapshotSerializer.IntakeRequest.class));
  }

  protected void setCurrentConfiguration(Configuration configuration) {
    synchronized (configLock) {
      this.currentConfiguration = configuration;
      configProvided = false;
    }
  }

  protected Configuration createConfig(LogProbe logProbe) {
    return createConfig(Arrays.asList(logProbe));
  }

  protected Configuration createConfig(Collection<LogProbe> logProbes) {
    return new Configuration(getAppId(), logProbes);
  }

  protected Configuration createConfig(
      Collection<LogProbe> logProbes,
      Configuration.FilterList allowList,
      Configuration.FilterList denyList) {
    return Configuration.builder()
        .setService(getAppId())
        .addLogProbes(logProbes)
        .addAllowList(allowList)
        .addDenyList(denyList)
        .build();
  }

  protected void assertCaptureArgs(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue capturedValue = context.getArguments().get(name);
    assertEquals(typeName, capturedValue.getType());
    Object objValue = capturedValue.getValue();
    if (objValue.getClass().isArray()) {
      assertEquals(value, Arrays.toString((Object[]) objValue));
    } else {
      assertEquals(value, String.valueOf(objValue));
    }
  }

  protected void assertCaptureLocals(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue localVar = context.getLocals().get(name);
    assertEquals(typeName, localVar.getType());
    assertEquals(value, localVar.getValue());
  }

  protected void assertCaptureReturnValue(CapturedContext context, String typeName, String value) {
    CapturedContext.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    assertEquals(value, returnValue.getValue());
  }

  protected void assertCaptureThrowable(
      CapturedContext.CapturedThrowable throwable, String typeName, String message) {
    assertNotNull(throwable);
    assertEquals(typeName, throwable.getType());
    assertEquals(message, throwable.getMessage());
  }

  protected static boolean logHasErrors(Path logFilePath, Function<String, Boolean> checker)
      throws IOException {
    long errorLines =
        Files.lines(logFilePath)
            .filter(
                it ->
                    it.contains(" ERROR ")
                        || it.contains("ASSERTION FAILED")
                        || it.contains("Error:")
                        || checker.apply(it))
            .peek(System.out::println)
            .count();
    boolean hasErrors = errorLines > 0;
    if (hasErrors) {
      System.out.println(
          "Test application log is containing errors. See full run logs in " + logFilePath);
    }
    return hasErrors;
  }

  private static class MockDispatcher extends okhttp3.mockwebserver.QueueDispatcher {
    private Function<RecordedRequest, MockResponse> dispatcher;

    @Override
    public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
      return dispatcher.apply(request);
    }

    public void setDispatcher(Function<RecordedRequest, MockResponse> dispatcher) {
      this.dispatcher = dispatcher;
    }
  }
}
