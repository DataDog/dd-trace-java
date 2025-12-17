package datadog.smoketest;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.test.util.Flaky;
import datadog.trace.test.util.NonRetryable;
import datadog.trace.util.TagsHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Flaky
@NonRetryable
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

  @ParameterizedTest(name = "Process tags enabled ''{0}''")
  @ValueSource(booleans = {true, false})
  @DisplayName("testTracer")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testTracer(boolean processTagsEnabled) throws Exception {
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(
                "org.springframework.web.servlet.DispatcherServlet",
                "doService",
                "(HttpServletRequest, HttpServletResponse)")
            .captureSnapshot(true)
            .build();
    AtomicBoolean requestReceived = new AtomicBoolean(false);
    registerIntakeRequestListener(
        intakeRequest -> {
          assertEquals(
              PROBE_ID.getId(), intakeRequest.getDebugger().getSnapshot().getProbe().getId());
          assertTrue(Pattern.matches("[0-9a-f]+", intakeRequest.getTraceId()));
          assertTrue(Pattern.matches("\\d+", intakeRequest.getSpanId()));
          assertFalse(
              logHasErrors(
                  logFilePath, it -> it.contains("TypePool$Resolution$NoSuchTypeException")));
          if (processTagsEnabled) {
            assertNotNull(intakeRequest.getProcessTags());
            assertTrue(
                intakeRequest
                    .getProcessTags()
                    .contains("entrypoint.name:" + TagsHelper.sanitize(DEBUGGER_TEST_APP_CLASS)));
          } else {
            assertNull(intakeRequest.getProcessTags());
          }
          requestReceived.set(true);
        });
    doTestTracer(logProbe, processTagsEnabled);
    processRequests(
        requestReceived::get,
        () -> String.format("timeout requestReceived=%s", requestReceived.get()));
  }

  @Test
  @DisplayName("testTracerDynamicLog")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
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
    AtomicBoolean requestReceived = new AtomicBoolean(false);
    registerIntakeRequestListener(
        intakeRequest -> {
          Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertTrue(Pattern.matches("[0-9a-f]+", intakeRequest.getTraceId()));
          assertTrue(Pattern.matches("\\d+", intakeRequest.getSpanId()));
          assertFalse(
              logHasErrors(
                  logFilePath, it -> it.contains("TypePool$Resolution$NoSuchTypeException")));
          requestReceived.set(true);
        });
    doTestTracer(logProbe);
    processRequests(
        requestReceived::get,
        () -> String.format("timeout requestReceived=%s", requestReceived.get()));
  }

  @Test
  @DisplayName("testTracerSameMethod")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testTracerSameMethod() throws Exception {
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where("datadog.smoketest.debugger.controller.WebController", "processWithArg", null)
            .captureSnapshot(true)
            .build();
    AtomicBoolean requestReceived = new AtomicBoolean(false);
    registerIntakeRequestListener(
        intakeRequest -> {
          Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertEquals(
              42, snapshot.getCaptures().getEntry().getArguments().get("argInt").getValue());
          // no locals captured
          assertNull(snapshot.getCaptures().getEntry().getLocals());
          assertTrue(Pattern.matches("[0-9a-f]+", intakeRequest.getTraceId()));
          assertTrue(Pattern.matches("\\d+", intakeRequest.getSpanId()));
          requestReceived.set(true);
        });
    doTestTracer(logProbe);
    processRequests(
        requestReceived::get,
        () -> String.format("timeout requestReceived=%s", requestReceived.get()));
  }

  @Test
  @DisplayName("testTracerLineSnapshotProbe")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testTracerLineSnapshotProbe() throws Exception {
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            // on line: System.out.println(argInt);
            .where("WebController.java", 15)
            .captureSnapshot(true)
            .build();
    AtomicBoolean requestReceived = new AtomicBoolean(false);
    registerIntakeRequestListener(
        intakeRequest -> {
          Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertEquals(
              42,
              snapshot.getCaptures().getLines().get(15).getArguments().get("argInt").getValue());
          assertTrue(Pattern.matches("[0-9a-f]+", intakeRequest.getTraceId()));
          assertTrue(Pattern.matches("\\d+", intakeRequest.getSpanId()));
          requestReceived.set(true);
        });
    doTestTracer(logProbe);
    processRequests(
        requestReceived::get,
        () -> String.format("timeout requestReceived=%s", requestReceived.get()));
  }

  @Test
  @DisplayName("testTracerLineDynamicLogProbe")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testTracerLineDynamicLogProbe() throws Exception {
    final String LOG_TEMPLATE = "processWithArg {argInt}";
    LogProbe logProbe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            // on line: System.out.println(argInt);
            .where("WebController.java", 15)
            .captureSnapshot(false)
            .build();
    AtomicBoolean requestReceived = new AtomicBoolean(false);
    registerIntakeRequestListener(
        intakeRequest -> {
          Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertEquals("processWithArg 42", intakeRequest.getMessage());
          assertTrue(Pattern.matches("[0-9a-f]+", intakeRequest.getTraceId()));
          assertTrue(Pattern.matches("\\d+", intakeRequest.getSpanId()));
          requestReceived.set(true);
        });
    doTestTracer(logProbe);
    processRequests(
        requestReceived::get,
        () -> String.format("timeout requestReceived=%s", requestReceived.get()));
  }

  private void doTestTracer(LogProbe logProbe) throws Exception {
    doTestTracer(logProbe, false);
  }

  private void doTestTracer(LogProbe logProbe, boolean enableProcessTags) throws Exception {
    setCurrentConfiguration(createConfig(logProbe));
    String httpPort = String.valueOf(PortUtils.randomOpenPort());
    ProcessBuilder processBuilder = createProcessBuilder(logFilePath, "--server.port=" + httpPort);
    if (!enableProcessTags) {
      processBuilder.environment().put("DD_EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED", "false");
    }
    targetProcess = processBuilder.start();
    // assert in logs app started
    waitForSpecificLogLine(
        logFilePath,
        "datadog.smoketest.debugger.SpringBootTestApplication - Started SpringBootTestApplication",
        Duration.ofMillis(100),
        Duration.ofSeconds(30));
    sendRequest("http://localhost:" + httpPort + "/greeting");
    /*
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
     */
  }

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
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
