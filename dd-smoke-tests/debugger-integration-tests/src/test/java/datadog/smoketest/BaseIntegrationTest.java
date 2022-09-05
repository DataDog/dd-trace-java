package datadog.smoketest;

import static datadog.smoketest.ProcessBuilderHelper.buildDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.SnapshotProbe;
import com.datadog.debugger.sink.SnapshotSink;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
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
  protected static final String SINGLE_EXPECTED_UPLOAD = "1";
  protected static final String PROBE_URL_PATH = "/configurations";
  protected static final String SNAPSHOT_URL_PATH = "/snapshots";
  protected static final int REQUEST_WAIT_TIMEOUT = 10;
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(), "reports", "testProcess." + DebuggerIntegrationTest.class.getName());
  private static final String INFO_CONTENT =
      "{\"endpoints\": [\"v0.4/traces\", \"debugger/v1/input\", \"v0.7/config\"]}";
  private static final MockResponse agentInfoResponse =
      new MockResponse().setResponseCode(200).setBody(INFO_CONTENT);

  protected MockWebServer snapshotServer;
  private MockDispatcher snapshotMockDispatcher;
  protected MockWebServer probeServer;
  private MockDispatcher probeMockDispatcher;

  private HttpUrl probeUrl;
  private HttpUrl snapshotUrl;
  protected Path logFilePath;
  protected Process targetProcess;
  private volatile Configuration currentConfiguration;

  @BeforeAll
  static void setupAll() throws Exception {
    Files.createDirectories(LOG_FILE_BASE);
  }

  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    probeServer = new MockWebServer();
    probeMockDispatcher = new MockDispatcher();
    probeMockDispatcher.setDispatcher(this::provideProbes);
    probeServer.setDispatcher(probeMockDispatcher);
    probeUrl = probeServer.url(PROBE_URL_PATH);
    LOG.info("ProbeServer on {}", probeServer.getPort());
    snapshotServer = new MockWebServer();
    snapshotUrl = snapshotServer.url(SNAPSHOT_URL_PATH);
    LOG.info("SnapshotServer on {}", snapshotServer.getPort());
    logFilePath = LOG_FILE_BASE.resolve(testInfo.getDisplayName() + ".log");
  }

  @AfterEach
  void teardown() throws Exception {
    if (targetProcess != null) {
      targetProcess.destroyForcibly();
    }
    try {
      snapshotServer.shutdown();
    } finally {
      probeServer.shutdown();
    }
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
            "-Ddd.debugger.enabled=true",
            "-Ddd.debugger.probe.url=http://localhost:" + probeServer.getPort() + PROBE_URL_PATH,
            "-Ddd.debugger.snapshot.url=http://localhost:"
                + snapshotServer.getPort()
                + SNAPSHOT_URL_PATH,
            "-Ddd.trace.agent.url=http://localhost:" + probeServer.getPort(),
            "-Ddd.debugger.upload.batch.size=1", // to verify each snapshot upload one by one
            "-Ddd.debugger.upload.flush.interval=100" // flush uploads every 100ms to have quick
            // tests
            ));
  }

  protected RecordedRequest retrieveSnapshotRequest() throws Exception {
    long ts = System.nanoTime();
    RecordedRequest request;

    do {
      request = snapshotServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
    } while (request != null
        && request.getBody().indexOf(ByteString.encodeUtf8("diagnostics")) > -1);
    long dur = System.nanoTime() - ts;
    LOG.info(
        "request retrieved in {} seconds", TimeUnit.SECONDS.convert(dur, TimeUnit.NANOSECONDS));
    return request;
  }

  protected ProbeStatus retrieveProbeStatusRequest() throws Exception {
    RecordedRequest request = snapshotServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
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

  private MockResponse provideProbes(RecordedRequest request) {
    if (request.getPath().equals("/info")) {
      return agentInfoResponse;
    }
    Configuration configuration = getCurrentConfiguration();
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
    return currentConfiguration;
  }

  protected JsonAdapter<List<SnapshotSink.IntakeRequest>> createAdapterForSnapshot() {
    return MoshiHelper.createMoshiSnapshot()
        .adapter(Types.newParameterizedType(List.class, SnapshotSink.IntakeRequest.class));
  }

  protected void setCurrentConfiguration(Configuration configuration) {
    this.currentConfiguration = configuration;
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
    assertEquals(value, capturedValue.getValue());
  }

  protected void assertCaptureLocals(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue localVar = context.getLocals().get(name);
    assertEquals(typeName, localVar.getType());
    assertEquals(value, localVar.getValue());
  }

  protected void assertCaptureFields(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue field = context.getFields().get(name);
    assertEquals(typeName, field.getType());
    assertEquals(value, field.getValue());
  }

  protected void assertCaptureFieldsRegEx(
      Snapshot.CapturedContext context, String name, String typeName, String regExValue) {
    Snapshot.CapturedValue field = context.getFields().get(name);
    assertEquals(typeName, field.getType());
    assertTrue(field.getValue(), Pattern.matches(regExValue, field.getValue()));
  }

  protected void assertCaptureFieldCount(Snapshot.CapturedContext context, int expectedFieldCount) {
    assertEquals(expectedFieldCount, context.getFields().size());
  }

  protected void assertCaptureReturnValue(
      Snapshot.CapturedContext context, String typeName, String value) {
    Snapshot.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    assertEquals(value, returnValue.getValue());
  }

  protected void assertCaptureReturnValueRegEx(
      Snapshot.CapturedContext context, String typeName, String regex) {
    Snapshot.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    assertTrue(returnValue.getValue(), Pattern.matches(regex, returnValue.getValue()));
  }

  protected void assertCaptureThrowable(
      Snapshot.CapturedContext context, String typeName, String message) {
    Snapshot.CapturedThrowable throwable = context.getThrowable();
    assertCaptureThrowable(throwable, typeName, message);
  }

  protected void assertCaptureThrowable(
      Snapshot.CapturedThrowable throwable, String typeName, String message) {
    assertNotNull(throwable);
    assertEquals(typeName, throwable.getType());
    assertEquals(message, throwable.getMessage());
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
