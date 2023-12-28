package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.TagsHelper;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

public class ServerAppDebuggerIntegrationTest extends BaseIntegrationTest {
  private static final String SERVER_DEBUGGER_TEST_APP_CLASS =
      "datadog.smoketest.debugger.ServerDebuggerTestApplication";
  protected static final String CONTROL_URL = "/control";
  protected static final ProbeId PROBE_ID = new ProbeId("123356536", 0);
  protected static final ProbeId PROBE_ID2 = new ProbeId("1233565367", 12);
  protected static final String TEST_APP_CLASS_NAME = "ServerDebuggerTestApplication";
  protected static final String FULL_METHOD_NAME = "fullMethod";
  protected static final String TRACED_METHOD_NAME = "tracedMethod";

  protected MockWebServer controlServer;
  protected HttpUrl controlUrl;
  private OkHttpClient httpClient = new OkHttpClient();
  protected String appUrl;

  @Override
  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    super.setup(testInfo);
    controlServer = new MockWebServer();
    // controlServer.setDispatcher(new ControlDispatcher());
    controlServer.start();
    LOG.info("ControlServer on {}", controlServer.getPort());
    controlUrl = controlServer.url(CONTROL_URL);
    startApp();
    appUrl = waitForAppStartedAndGetUrl();
  }

  @Override
  @AfterEach
  void teardown() throws Exception {
    stopApp(appUrl);
    controlServer.shutdown();
    super.teardown();
  }

  @Override
  protected String getAppClass() {
    return SERVER_DEBUGGER_TEST_APP_CLASS;
  }

  @Override
  protected String getAppId() {
    return TagsHelper.sanitize("ServerDebuggerTestApplication");
  }

  protected void stopApp(String appUrl) throws IOException {
    try {
      sendRequest(appUrl + "/stop");
    } catch (Exception ex) {
      LOG.warn("Exception while stopping server app", ex);
    }
    LOG.info("Stop done");
  }

  protected List<Snapshot> waitForSnapshots() throws Exception {
    RecordedRequest snapshotRequest = retrieveSnapshotRequest();
    assertNotNull(snapshotRequest);
    String bodyStr = snapshotRequest.getBody().readUtf8();
    LOG.info("got snapshot: {}", bodyStr);
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
    List<JsonSnapshotSerializer.IntakeRequest> intakeRequests = adapter.fromJson(bodyStr);
    return intakeRequests.stream()
        .map(intakeRequest -> intakeRequest.getDebugger().getSnapshot())
        .collect(Collectors.toList());
  }

  protected void execute(String appUrl, String methodName) throws IOException {
    execute(appUrl, methodName, null);
  }

  protected void execute(String appUrl, String methodName, String arg) throws IOException {
    datadogAgentServer.enqueue(EMPTY_200_RESPONSE); // expect 1 snapshot
    String executeFormat = arg != null ? "/execute?methodname=%s&arg=%s" : "/execute?methodname=%s";
    String url = String.format(appUrl + executeFormat, methodName, arg);
    sendRequest(url);
    LOG.info("Execution done");
  }

  protected void waitForInstrumentation(String appUrl) throws Exception {
    String url =
        String.format(
            appUrl + "/waitForInstrumentation?classname=%s", SERVER_DEBUGGER_TEST_APP_CLASS);
    LOG.info("waitForInstrumentation with url={}", url);
    sendRequest(url);
    AtomicBoolean received = new AtomicBoolean();
    AtomicBoolean installed = new AtomicBoolean();
    registerProbeStatusListener(
        probeStatus -> {
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.RECEIVED) {
            received.set(true);
          }
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.INSTALLED) {
            installed.set(true);
          }
        });
    processRequests(() -> received.get() && installed.get());
    LOG.info("instrumentation done");
  }

  protected void waitForAProbeStatus(ProbeStatus.Status status) throws Exception {
    AtomicBoolean statusResult = new AtomicBoolean();
    registerProbeStatusListener(
        probeStatus -> {
          statusResult.set(probeStatus.getDiagnostics().getStatus() == status);
        });
    processRequests(statusResult::get);
  }

  protected void waitForReTransformation(String appUrl) throws IOException {
    String url =
        String.format(
            appUrl + "/waitForReTransformation?classname=%s", SERVER_DEBUGGER_TEST_APP_CLASS);
    sendRequest(url);
    LOG.info("re-transformation done");
  }

  protected void startApp() throws IOException {
    controlServer.enqueue(EMPTY_200_RESPONSE); // ack response
    targetProcess = createProcessBuilder(logFilePath, controlUrl.toString()).start();
  }

  protected String waitForAppStartedAndGetUrl() throws InterruptedException, EOFException {
    RecordedRequest recordedRequest = controlServer.takeRequest(10, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    String appUrl = recordedRequest.getBody().readUtf8Line();
    LOG.info("AppUrl = " + appUrl);
    return appUrl;
  }

  protected void addProbe(LogProbe logProbe) {
    datadogAgentServer.enqueue(EMPTY_200_RESPONSE); // expect RECEIVED status
    datadogAgentServer.enqueue(EMPTY_200_RESPONSE); // expect INSTALLED status
    setCurrentConfiguration(createConfig(logProbe));
  }

  protected void addProbe(SpanDecorationProbe spanDecorationProbe) {
    datadogAgentServer.enqueue(EMPTY_200_RESPONSE); // expect RECEIVED status
    datadogAgentServer.enqueue(EMPTY_200_RESPONSE); // expect INSTALLED status
    setCurrentConfiguration(createSpanDecoConfig(spanDecorationProbe));
  }

  protected void sendRequest(String url) throws IOException {
    Request request = new Request.Builder().url(url).get().build();
    try (Response response = httpClient.newCall(request).execute()) {}
  }
}
