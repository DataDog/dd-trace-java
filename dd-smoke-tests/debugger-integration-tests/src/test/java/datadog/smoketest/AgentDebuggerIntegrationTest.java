package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.JsonAdapter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class AgentDebuggerIntegrationTest extends SimpleAppDebuggerIntegrationTest {
  @Test
  @DisplayName("testLatestJdk")
  void testLatestJdk() throws Exception {
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe = LogProbe.builder().where("App", "getGreeting").build();
    setCurrentConfiguration(createConfig(probe));
    String classpath = System.getProperty("datadog.smoketest.shadowJar.external.path");
    if (classpath == null) {
      return; // execute test only if classpath is provided for the latest jdk
    }
    List<String> commandParams = getDebuggerCommandParams();
    targetProcess =
        ProcessBuilderHelper.createProcessBuilder(
                classpath, commandParams, logFilePath, "App", EXPECTED_UPLOADS)
            .start();
    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertFalse(logHasErrors(logFilePath, it -> false));
    String bodyStr = request.getBody().readUtf8();
    LOG.info("got snapshot: {}", bodyStr);
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
    Snapshot snapshot = adapter.fromJson(bodyStr).get(0).getDebugger().getSnapshot();
    assertNotNull(snapshot);
  }

  @Test
  @DisplayName("testShutdown")
  void testShutdown() throws Exception {
    final String METHOD_NAME = "emptyMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, METHOD_NAME).build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();

    RecordedRequest request = retrieveSnapshotRequest();
    assertFalse(logHasErrors(logFilePath, it -> false));
    assertNotNull(request);
    assertTrue(request.getBodySize() > 0);

    // Wait for the app exit with some extra time.
    // The expectation is that agent doesn't prevent app from exiting.
    assertTrue(targetProcess.waitFor(REQUEST_WAIT_TIMEOUT + 10, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("testDestroy")
  void testDestroy() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, METHOD_NAME).build();
    setCurrentConfiguration(createConfig(probe));
    datadogAgentServer.enqueue(
        new MockResponse()
            .setHeadersDelay(REQUEST_WAIT_TIMEOUT * 2, TimeUnit.SECONDS)
            .setResponseCode(200));
    // wait for 3 snapshots (2 status + 1 snapshot)
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();

    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertTrue(request.getBodySize() > 0);
    retrieveSnapshotRequest();
    targetProcess.destroy();
    // Wait for the app exit with some extra time.
    // The expectation is that agent doesn't prevent app from exiting.
    assertTrue(targetProcess.waitFor(REQUEST_WAIT_TIMEOUT + 10, TimeUnit.SECONDS));
  }
}
