package datadog.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.SnapshotProbe;
import com.datadog.debugger.util.TagsHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebuggerIntegrationTest extends BaseIntegrationTest {
  private static final Logger LOG = LoggerFactory.getLogger(DebuggerIntegrationTest.class);
  private static final String DEBUGGER_TEST_APP_CLASS =
      "datadog.smoketest.debugger.DebuggerTestApplication";

  private static final String PROBE_ID = "123356536";

  @Override
  protected String getAppClass() {
    return DEBUGGER_TEST_APP_CLASS;
  }

  @Override
  protected String getAppId() {
    return TagsHelper.sanitize("DebuggerTestApplication");
  }

  @Test
  @DisplayName("testLatestJdk")
  void testLatestJdk() throws Exception {
    SnapshotProbe probe = SnapshotProbe.builder().where("App", "getGreeting").build();
    setCurrentConfiguration(createConfig(probe));
    String classpath = System.getProperty("datadog.smoketest.shadowJar.external.path");
    if (classpath == null) {
      return; // execute test only if classpath is provided for the latest jdk
    }
    List<String> commandParams = getDebuggerCommandParams();
    targetProcess =
        ProcessBuilderHelper.createProcessBuilder(classpath, commandParams, logFilePath, "App", "3")
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
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("DebuggerTestApplication", METHOD_NAME)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, "3").start();

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
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("DebuggerTestApplication", METHOD_NAME)
            .build();
    setCurrentConfiguration(createConfig(probe));
    datadogAgentServer.enqueue(
        new MockResponse()
            .setHeadersDelay(REQUEST_WAIT_TIMEOUT * 2, TimeUnit.SECONDS)
            .setResponseCode(200));
    // wait for 3 snapshots (2 status + 1 snapshot)
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, "3").start();

    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertTrue(request.getBodySize() > 0);
    retrieveSnapshotRequest();
    targetProcess.destroy();
    // Wait for the app exit with some extra time.
    // The expectation is that agent doesn't prevent app from exiting.
    assertTrue(targetProcess.waitFor(REQUEST_WAIT_TIMEOUT + 10, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("testInaccessibleObject")
  void testInaccessibleObject() throws Exception {
    final String METHOD_NAME = "managementMethod";
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("DebuggerTestApplication", METHOD_NAME)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, "3").start();
    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertFalse(logHasErrors(logFilePath, it -> false));
    Thread.sleep(10000);
  }

  @Test
  @DisplayName("testFullMethod")
  void testFullMethod() throws Exception {
    final String METHOD_NAME = "fullMethod";
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("DebuggerTestApplication", METHOD_NAME)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, "3").start();
    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertFalse(logHasErrors(logFilePath, it -> false));
    String bodyStr = request.getBody().readUtf8();
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
    System.out.println(bodyStr);
    Snapshot snapshot = adapter.fromJson(bodyStr).get(0).getDebugger().getSnapshot();
    assertEquals("123356536", snapshot.getProbe().getId());
    assertFullMethodCaptureArgs(snapshot.getCaptures().getEntry());
    assertEquals(0, snapshot.getCaptures().getEntry().getLocals().size());
    assertNull(snapshot.getCaptures().getEntry().getThrowable());
    assertNull(snapshot.getCaptures().getEntry().getFields());
    assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
    assertCaptureReturnValue(
        snapshot.getCaptures().getReturn(),
        "java.lang.String",
        "42, foobar, 3.42, {key1=val1, key2=val2, key3=val3}, var1,var2,var3");
    assertNotNull(snapshot.getCaptures().getReturn().getLocals());
    assertEquals(1, snapshot.getCaptures().getReturn().getLocals().size()); // @return
    assertNull(snapshot.getCaptures().getReturn().getThrowable());
    assertNull(snapshot.getCaptures().getReturn().getFields());
  }

  private void assertFullMethodCaptureArgs(Snapshot.CapturedContext context) {
    if (Platform.isJ9()) {
      // skip for J9/OpenJ9 as we cannot get local variable debug info.
      return;
    }
    assertCaptureArgs(context, "argInt", "int", "42");
    assertCaptureArgs(context, "argStr", "java.lang.String", "foobar");
    assertCaptureArgs(context, "argDouble", "double", "3.42");
    assertCaptureArgs(context, "argMap", "java.util.Map", "{key1=val1, key2=val2, key3=val3}");
    assertCaptureArgs(context, "argVar", "java.lang.String[]", "[var1, var2, var3]");
  }

  @Test
  @DisplayName("testMultiProbes")
  void testMultiProbes() throws Exception {
    final String METHOD_NAME = "fullMethod";
    List<SnapshotProbe> probes = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      probes.add(
          SnapshotProbe.builder()
              .probeId("12335653" + i)
              .where("DebuggerTestApplication", METHOD_NAME)
              .build());
    }
    setCurrentConfiguration(createConfig(probes));
    final int NB_SNAPSHOTS = 10;
    int expectedSnapshotUploads = NB_SNAPSHOTS * 3;
    targetProcess =
        createProcessBuilder(logFilePath, METHOD_NAME, String.valueOf(expectedSnapshotUploads))
            .start();
    Set<String> probeIds = new HashSet<>();
    int snapshotCount = 0;
    while (snapshotCount < NB_SNAPSHOTS) {
      RecordedRequest request = retrieveSnapshotRequest();
      assertNotNull(request);
      String bodyStr = request.getBody().readUtf8();
      JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
      List<JsonSnapshotSerializer.IntakeRequest> intakeRequests = adapter.fromJson(bodyStr);
      snapshotCount += intakeRequests.size();
      System.out.println("received " + intakeRequests.size() + " snapshots");
      for (JsonSnapshotSerializer.IntakeRequest intakeRequest : intakeRequests) {
        Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
        probeIds.add(snapshot.getProbe().getId());
      }
    }
    assertEquals(NB_SNAPSHOTS, probeIds.size());
    for (int i = 0; i < NB_SNAPSHOTS; i++) {
      assertTrue(probeIds.contains("12335653" + i));
    }
    assertFalse(logHasErrors(logFilePath, it -> false));
  }

  private static void assertContainsLogLine(Path logFilePath, String containsLine)
      throws IOException {
    boolean[] result = new boolean[] {false};
    Files.lines(logFilePath)
        .forEach(
            it -> {
              if (it.contains(containsLine)) {
                System.out.println(it);
                result[0] = true;
              }
            });
    assertTrue(result[0]);
  }
}
