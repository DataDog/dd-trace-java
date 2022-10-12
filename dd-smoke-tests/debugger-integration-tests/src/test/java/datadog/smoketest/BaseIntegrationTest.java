package datadog.smoketest;

import static datadog.smoketest.ProcessBuilderHelper.buildDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.SnapshotProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private static final MockResponse agentInfoResponse =
      new MockResponse().setResponseCode(200).setBody(INFO_CONTENT);

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
            "-Ddd.remote_config.enabled=true",
            "-Ddd.remote_config.initial.poll.interval=1",
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
    if (request.getPath().equals("/info")) {
      return agentInfoResponse;
    }
    if (request.getPath().startsWith(SNAPSHOT_URL_PATH)) {
      return new MockResponse().setResponseCode(200);
    }
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
          MoshiHelper.createMoshiConfig().adapter(Configuration.class);
      String json = adapter.toJson(configuration);
      String remoteConfigJson = RemoteConfigHelper.encode(json, configuration.getId());
      return new MockResponse().setResponseCode(200).setBody(remoteConfigJson);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
    return MoshiHelper.createMoshiSnapshot()
        .adapter(
            Types.newParameterizedType(List.class, JsonSnapshotSerializer.IntakeRequest.class));
  }

  protected void setCurrentConfiguration(Configuration configuration) {
    synchronized (configLock) {
      this.currentConfiguration = configuration;
      configProvided = false;
    }
  }

  protected Configuration createConfig(SnapshotProbe snapshotProbe) {
    return createConfig(Arrays.asList(snapshotProbe));
  }

  protected Configuration createConfig(Collection<SnapshotProbe> snapshotProbes) {
    return new Configuration(getAppId(), 2, snapshotProbes);
  }

  protected Configuration createConfig(
      Collection<SnapshotProbe> snapshotProbes,
      Configuration.FilterList allowList,
      Configuration.FilterList denyList) {
    return new Configuration(getAppId(), 2, snapshotProbes, null, allowList, denyList, null, null);
  }

  protected void assertCaptureArgs(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue capturedValue = context.getArguments().get(name);
    assertEquals(typeName, capturedValue.getType());
    Object objValue = capturedValue.getValue();
    if (objValue.getClass().isArray()) {
      assertEquals(value, Arrays.toString((Object[]) objValue));
    } else {
      assertEquals(value, String.valueOf(objValue));
    }
  }

  protected void assertCaptureLocals(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue localVar = context.getLocals().get(name);
    assertEquals(typeName, localVar.getType());
    assertEquals(value, localVar.getValue());
  }

  protected void assertCaptureReturnValue(
      Snapshot.CapturedContext context, String typeName, String value) {
    Snapshot.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    assertEquals(value, returnValue.getValue());
  }

  protected void assertCaptureThrowable(
      Snapshot.CapturedThrowable throwable, String typeName, String message) {
    assertNotNull(throwable);
    assertEquals(typeName, throwable.getType());
    assertEquals(message, throwable.getMessage());
  }

  protected static boolean logHasErrors(Path logFilePath, Function<String, Boolean> checker)
      throws IOException {
    final AtomicBoolean hasErrors = new AtomicBoolean();
    Files.lines(logFilePath)
        .forEach(
            it -> {
              if (it.contains(" ERROR ")
                  || it.contains("ASSERTION FAILED")
                  || it.contains("Error:")) {
                System.out.println(it);
                hasErrors.set(true);
              }
              hasErrors.set(hasErrors.get() || checker.apply(it));
            });
    if (hasErrors.get()) {
      System.out.println(
          "Test application log is containing errors. See full run logs in " + logFilePath);
    }
    return hasErrors.get();
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
