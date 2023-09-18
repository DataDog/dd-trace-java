package datadog.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.TagsHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class TracerDebuggerIntegrationTest extends BaseIntegrationTest {

  private static final String DEBUGGER_TEST_APP_CLASS =
      "datadog.smoketest.debugger.SpringBootTestApplication";
  private static final ProbeId PROBE_ID = new ProbeId("123356536", 1);

  @Override
  protected String getAppClass() {
    return DEBUGGER_TEST_APP_CLASS;
  }

  @Override
  protected String getAppId() {
    return TagsHelper.sanitize("SpringBootTestApplication");
  }

  @Test
  @DisplayName("testTracer")
  void testTracer() throws Exception {
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(
                "org.springframework.web.servlet.DispatcherServlet",
                "doService",
                "(HttpServletRequest, HttpServletResponse)")
            .captureSnapshot(true)
            .build();
    JsonSnapshotSerializer.IntakeRequest request = doTestTracer(logProbe);
    Snapshot snapshot = request.getDebugger().getSnapshot();
    assertEquals("123356536", snapshot.getProbe().getId());
    assertTrue(Pattern.matches("[0-9a-f]+", request.getTraceId()));
    assertTrue(Pattern.matches("\\d+", request.getSpanId()));
    assertFalse(
        logHasErrors(logFilePath, it -> it.contains("TypePool$Resolution$NoSuchTypeException")));
  }

  @Test
  @DisplayName("testTracerDynamicLog")
  void testTracerDynamicLog() throws Exception {
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(
                "org.springframework.web.servlet.DispatcherServlet",
                "doService",
                "(HttpServletRequest, HttpServletResponse)")
            .captureSnapshot(false)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    JsonSnapshotSerializer.IntakeRequest request = doTestTracer(logProbe);
    Snapshot snapshot = request.getDebugger().getSnapshot();
    assertEquals("123356536", snapshot.getProbe().getId());
    assertTrue(Pattern.matches("[0-9a-f]+", request.getTraceId()));
    assertTrue(Pattern.matches("\\d+", request.getSpanId()));
    assertFalse(
        logHasErrors(logFilePath, it -> it.contains("TypePool$Resolution$NoSuchTypeException")));
  }

  @Test
  @DisplayName("testTracerSameMethod")
  void testTracerSameMethod() throws Exception {
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where("datadog.smoketest.debugger.controller.WebController", "processWithArg", null)
            .captureSnapshot(true)
            .build();
    JsonSnapshotSerializer.IntakeRequest request = doTestTracer(logProbe);
    Snapshot snapshot = request.getDebugger().getSnapshot();
    assertEquals("123356536", snapshot.getProbe().getId());
    assertEquals(42, snapshot.getCaptures().getEntry().getArguments().get("argInt").getValue());
    assertTrue(Pattern.matches("[0-9a-f]+", request.getTraceId()));
    assertTrue(Pattern.matches("\\d+", request.getSpanId()));
  }

  private JsonSnapshotSerializer.IntakeRequest doTestTracer(LogProbe logProbe) throws Exception {
    setCurrentConfiguration(createConfig(logProbe));
    String httpPort = String.valueOf(PortUtils.randomOpenPort());
    targetProcess = createProcessBuilder(logFilePath, "--server.port=" + httpPort).start();
    // assert in logs app started
    waitForSpecificLogLine(
        logFilePath,
        "datadog.smoketest.debugger.SpringBootTestApplication - Started SpringBootTestApplication",
        Duration.ofMillis(100),
        Duration.ofSeconds(30));
    sendRequest("http://localhost:" + httpPort + "/greeting");
    RecordedRequest snapshotRequest = retrieveSnapshotRequest();
    if (snapshotRequest == null) {
      System.out.println("retry instrumentation because probable race with Tracer...");
      // may encounter a race with Tracer, try again to re-instrument by removing config and
      // re-installing instrumentation
      synchronized (configLock) {
        setCurrentConfiguration(null);
        configLock.wait(10_000);
        if (!isConfigProvided()) {
          System.out.println("Empty config was not provided!");
        }
      }
      setCurrentConfiguration(createConfig(logProbe));
      snapshotRequest = retrieveSnapshotRequest();
    }
    assertNotNull(snapshotRequest);
    String bodyStr = snapshotRequest.getBody().readUtf8();
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
    System.out.println(bodyStr);
    return adapter.fromJson(bodyStr).get(0);
  }

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true");
    commandParams.add(
        "-Ddd.trace.methods=datadog.smoketest.debugger.controller.WebController[processWithArg]");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  private void sendRequest(String url) {
    OkHttpClient client = new OkHttpClient.Builder().build();
    Request request = new Request.Builder().url(url).get().build();
    try {
      client.newCall(request).execute();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private static void waitForSpecificLogLine(
      Path logFilePath, String line, Duration sleep, Duration timeout) throws IOException {
    boolean[] result = new boolean[] {false};
    long total = sleep.toNanos() == 0 ? 0 : timeout.toNanos() / sleep.toNanos();
    int i = 0;
    while (i < total && !result[0]) {
      Files.lines(logFilePath)
          .forEach(
              it -> {
                if (it.contains(line)) {
                  result[0] = true;
                }
              });
      LockSupport.parkNanos(sleep.toNanos());
      i++;
    }
  }
}
