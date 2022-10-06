package datadog.smoketest;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.SnapshotProbe;
import com.datadog.debugger.util.TagsHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.EOFException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class ServerDebuggerIntegrationTest extends BaseIntegrationTest {
  private static final String SERVER_DEBUGGER_TEST_APP_CLASS =
      "datadog.smoketest.debugger.ServerDebuggerTestApplication";
  private static final String CONTROL_URL = "/control";
  private static final MockResponse EMPTY_HTTP_200 = new MockResponse().setResponseCode(200);
  private static final String PROBE_ID = "123356536";
  private static final String TEST_APP_CLASS_NAME = "ServerDebuggerTestApplication";
  private static final String FULL_METHOD_NAME = "fullMethod";

  private MockWebServer controlServer;
  private HttpUrl controlUrl;
  private OkHttpClient httpClient = new OkHttpClient();

  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    super.setup(testInfo);
    controlServer = new MockWebServer();
    // controlServer.setDispatcher(new ControlDispatcher());
    controlServer.start();
    controlUrl = controlServer.url(CONTROL_URL);
  }

  @AfterEach
  void teardown() throws Exception {
    super.teardown();
    controlServer.shutdown();
  }

  @Override
  protected String getAppClass() {
    return SERVER_DEBUGGER_TEST_APP_CLASS;
  }

  @Override
  protected String getAppId() {
    return TagsHelper.sanitize("ServerDebuggerTestApplication");
  }

  @Test
  @DisplayName("testAddRemoveProbes")
  void testAddRemoveProbes() throws Exception {
    controlServer.enqueue(EMPTY_HTTP_200); // ack response
    targetProcess = createProcessBuilder(logFilePath, controlUrl.toString()).start();
    String appUrl = waitForAppStartedAndGetUrl();
    SnapshotProbe snapshotProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, FULL_METHOD_NAME)
            .build();
    addProbe(snapshotProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, FULL_METHOD_NAME);
    List<Snapshot> snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());
    setCurrentConfiguration(createConfig(Collections.emptyList())); // remove probes
    waitForReTransformation(appUrl);
    addProbe(snapshotProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, FULL_METHOD_NAME);
    snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());
    stopApp(appUrl);
  }

  @Test
  @DisplayName("testDisableEnableProbes")
  void testDisableEnableProbes() throws Exception {
    controlServer.enqueue(EMPTY_HTTP_200); // ack response
    targetProcess = createProcessBuilder(logFilePath, controlUrl.toString()).start();
    String appUrl = waitForAppStartedAndGetUrl();
    SnapshotProbe snapshotProbe =
        SnapshotProbe.builder().probeId(PROBE_ID).where(TEST_APP_CLASS_NAME, "fullMethod").build();
    addProbe(snapshotProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, FULL_METHOD_NAME);
    List<Snapshot> snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());
    SnapshotProbe disabledSnapshotProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .active(false)
            .where(TEST_APP_CLASS_NAME, "fullMethod")
            .build();
    addProbe(disabledSnapshotProbe);
    waitForReTransformation(appUrl);
    addProbe(snapshotProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, FULL_METHOD_NAME);
    snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());
    stopApp(appUrl);
  }

  @Test
  @DisplayName("testDisableEnableProbesUsingDenyList")
  void testDisableEnableProbesUsingDenyList() throws Exception {
    controlServer.enqueue(EMPTY_HTTP_200); // ack response
    targetProcess = createProcessBuilder(logFilePath, controlUrl.toString()).start();
    String appUrl = waitForAppStartedAndGetUrl();
    SnapshotProbe snapshotProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, FULL_METHOD_NAME)
            .build();
    addProbe(snapshotProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, FULL_METHOD_NAME);
    List<Snapshot> snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());

    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect BLOCKED status
    Configuration.FilterList denyList =
        new Configuration.FilterList(asList("datadog.smoketest.debugger"), Collections.emptyList());
    setCurrentConfiguration(createConfig(asList(snapshotProbe), null, denyList));
    waitForReTransformation(appUrl);
    waitForAProbeStatus(ProbeStatus.Status.BLOCKED);

    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect INSTALLED status
    addProbe(snapshotProbe);
    // waitForInstrumentation(appUrl);
    waitForReTransformation(appUrl);
    waitForAProbeStatus(ProbeStatus.Status.INSTALLED);
    execute(appUrl, FULL_METHOD_NAME);
    snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());
    stopApp(appUrl);
  }

  @Test
  @DisplayName("testDisableEnableProbesUsingAllowList")
  void testDisableEnableProbesUsingAllowList() throws Exception {
    controlServer.enqueue(EMPTY_HTTP_200); // ack response
    targetProcess = createProcessBuilder(logFilePath, controlUrl.toString()).start();
    String appUrl = waitForAppStartedAndGetUrl();
    SnapshotProbe snapshotProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, FULL_METHOD_NAME)
            .build();
    addProbe(snapshotProbe);
    waitForInstrumentation(appUrl);
    execute(appUrl, FULL_METHOD_NAME);
    List<Snapshot> snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());

    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect BLOCKED status
    Configuration.FilterList allowList =
        new Configuration.FilterList(asList("datadog.not.debugger"), Collections.emptyList());
    setCurrentConfiguration(createConfig(asList(snapshotProbe), allowList, null));
    waitForReTransformation(appUrl);
    waitForAProbeStatus(ProbeStatus.Status.BLOCKED);

    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect INSTALLED status
    addProbe(snapshotProbe);
    // waitForInstrumentation(appUrl);
    waitForReTransformation(appUrl);
    waitForAProbeStatus(ProbeStatus.Status.INSTALLED);
    execute(appUrl, FULL_METHOD_NAME);
    snapshots = waitForSnapshots();
    assertEquals(1, snapshots.size());
    assertEquals(FULL_METHOD_NAME, snapshots.get(0).getProbe().getLocation().getMethod());
    stopApp(appUrl);
  }

  @Test
  @DisplayName("testProbeStatusError")
  public void testProbeStatusError() throws Exception {
    controlServer.enqueue(EMPTY_HTTP_200); // ack response
    targetProcess = createProcessBuilder(logFilePath, controlUrl.toString()).start();
    String appUrl = waitForAppStartedAndGetUrl();
    SnapshotProbe snapshotProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(TEST_APP_CLASS_NAME, "unknownMethodName")
            .build();
    addProbe(snapshotProbe);
    // statuses could be received out of order
    HashMap<ProbeStatus.Status, ProbeStatus.Diagnostics> statuses = new HashMap<>();
    ProbeStatus.Diagnostics diagnostics = retrieveProbeStatusRequest().getDiagnostics();
    statuses.put(diagnostics.getStatus(), diagnostics);
    diagnostics = retrieveProbeStatusRequest().getDiagnostics();
    statuses.put(diagnostics.getStatus(), diagnostics);
    Assert.assertTrue(statuses.containsKey(ProbeStatus.Status.RECEIVED));
    Assert.assertEquals(
        "Cannot find datadog/smoketest/debugger/ServerDebuggerTestApplication::unknownMethodName",
        statuses.get(ProbeStatus.Status.ERROR).getException().getMessage());
  }

  private void stopApp(String appUrl) throws IOException {
    try {
      sendRequest(appUrl + "/stop");
    } catch (Exception ex) {
      LOG.warn("Exception while stopping server app", ex);
    }
    LOG.info("Stop done");
  }

  private List<Snapshot> waitForSnapshots() throws Exception {
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

  private void execute(String appUrl, String methodName) throws IOException {
    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect 1 snapshot
    String url = String.format(appUrl + "/execute?methodname=%s", methodName);
    sendRequest(url);
    LOG.info("Execution done");
  }

  private void waitForInstrumentation(String appUrl) throws Exception {
    String url =
        String.format(
            appUrl + "/waitForInstrumentation?classname=%s", SERVER_DEBUGGER_TEST_APP_CLASS);
    sendRequest(url);
    // statuses could be received out of order
    HashSet<ProbeStatus.Status> statuses = new HashSet<>();
    statuses.add(retrieveProbeStatusRequest().getDiagnostics().getStatus());
    statuses.add(retrieveProbeStatusRequest().getDiagnostics().getStatus());
    assertTrue(statuses.contains(ProbeStatus.Status.RECEIVED));
    assertTrue(statuses.contains(ProbeStatus.Status.INSTALLED));
    LOG.info("instrumentation done");
  }

  private void waitForAProbeStatus(ProbeStatus.Status status) throws Exception {
    ProbeStatus.Status receivedStatus = retrieveProbeStatusRequest().getDiagnostics().getStatus();
    assertEquals(receivedStatus, status);
  }

  private void waitForReTransformation(String appUrl) throws IOException {
    String url =
        String.format(
            appUrl + "/waitForReTransformation?classname=%s", SERVER_DEBUGGER_TEST_APP_CLASS);
    sendRequest(url);
    LOG.info("re-transformation done");
  }

  private String waitForAppStartedAndGetUrl() throws InterruptedException, EOFException {
    RecordedRequest recordedRequest = controlServer.takeRequest(10, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    String appUrl = recordedRequest.getBody().readUtf8Line();
    LOG.info("AppUrl = " + appUrl);
    return appUrl;
  }

  private void addProbe(SnapshotProbe snapshotProbe) {
    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect RECEIVED status
    datadogAgentServer.enqueue(EMPTY_HTTP_200); // expect INSTALLED status
    setCurrentConfiguration(createConfig(snapshotProbe));
  }

  private void sendRequest(String url) throws IOException {
    Request request = new Request.Builder().url(url).get().build();
    try (Response response = httpClient.newCall(request).execute()) {}
  }
}
