package datadog.smoketest;

import static datadog.smoketest.debugger.TestApplicationHelper.waitForSpecificLine;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.probe.LogProbe;
import datadog.trace.test.util.NonRetryable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NonRetryable
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
    AtomicBoolean snapshotReceived = new AtomicBoolean(false);
    registerSnapshotListener(
        snapshot -> {
          snapshotReceived.set(true);
        });
    processRequests(
        snapshotReceived::get,
        () -> String.format("timeout snapshotReceived=%s", snapshotReceived.get()));
    assertFalse(logHasErrors(logFilePath, it -> false));
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
    AtomicBoolean snapshotReceived = new AtomicBoolean(false);
    registerSnapshotListener(
        snapshot -> {
          snapshotReceived.set(true);
        });
    processRequests(
        snapshotReceived::get,
        () -> String.format("timeout snapshotReceived=%s", snapshotReceived.get()));
    assertFalse(logHasErrors(logFilePath, it -> false));
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
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean snapshotReceived = new AtomicBoolean(false);
    registerSnapshotListener(
        snapshot -> {
          snapshotReceived.set(true);
        });
    processRequests(
        snapshotReceived::get,
        () -> String.format("timeout snapshotReceived=%s", snapshotReceived.get()));
    targetProcess.destroy();
    // Wait for the app exit with some extra time.
    // The expectation is that agent doesn't prevent app from exiting.
    assertTrue(targetProcess.waitFor(REQUEST_WAIT_TIMEOUT + 10, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("testEndpoints")
  void testEndpoints() throws Exception {
    setCurrentConfiguration(createConfig(Collections.emptyList()));
    targetProcess = createProcessBuilder(logFilePath, "", "").start();
    waitForSpecificLine(
        logFilePath.toString(),
        "INFO com.datadog.debugger.agent.DebuggerAgent - Started Dynamic Instrumentation",
        null);
    Assertions.assertFalse(
        logHasErrors(
            logFilePath,
            line -> {
              if (line.contains("Started BatchUploader[Diagnostics]")) {
                return !line.matches(
                    ".* Started BatchUploader\\[Diagnostics] with target url http://localhost:\\d+/debugger/v1/diagnostics");
              }
              if (line.contains("Started BatchUploader[Snapshots]")) {
                return !line.matches(
                    ".* Started BatchUploader\\[Snapshots] with target url http://localhost:\\d+/debugger/v1/diagnostics");
              }
              if (line.contains("Started BatchUploader[Logs]")) {
                return !line.matches(
                    ".* Started BatchUploader\\[Logs] with target url http://localhost:\\d+/debugger/v1/diagnostics");
              }
              if (line.contains("Started BatchUploader[SymDB]")) {
                return !line.matches(
                    ".* Started BatchUploader\\[SymDB] with target url http://localhost:\\d+/symdb/v1/input");
              }
              return false;
            }));
  }
}
