package datadog.smoketest;

import static com.datadog.debugger.el.DSL.and;
import static com.datadog.debugger.el.DSL.eq;
import static com.datadog.debugger.el.DSL.gt;
import static com.datadog.debugger.el.DSL.len;
import static com.datadog.debugger.el.DSL.nullValue;
import static com.datadog.debugger.el.DSL.ref;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.el.DSL.when;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.test.util.Flaky;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

public class LogProbesIntegrationTest extends SimpleAppDebuggerIntegrationTest {
  @Test
  @DisplayName("testInaccessibleObject")
  void testInaccessibleObject() throws Exception {
    final String METHOD_NAME = "managementMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, METHOD_NAME).build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertFalse(logHasErrors(logFilePath, it -> false));
  }

  @Flaky
  @Test
  @DisplayName("testFullMethod")
  void testFullMethod() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .captureSnapshot(true)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean snapshotReceived = new AtomicBoolean();
    registerSnapshotListener(
        snapshot -> {
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertFullMethodCaptureArgs(snapshot.getCaptures().getEntry());
          assertNull(snapshot.getCaptures().getEntry().getLocals());
          assertNull(snapshot.getCaptures().getEntry().getCapturedThrowable());
          assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
          assertCaptureReturnValue(
              snapshot.getCaptures().getReturn(),
              "java.lang.String",
              "42, foobar, 3.42, {key1=val1, key2=val2, key3=val3}, var1,var2,var3");
          assertNotNull(snapshot.getCaptures().getReturn().getLocals());
          // ex & @return are the only locals
          assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
          assertNull(snapshot.getCaptures().getReturn().getCapturedThrowable());
          snapshotReceived.set(true);
        });
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(() -> snapshotReceived.get() && statusResult.get());
  }

  @Test
  @DisplayName("testFullMethodWithCondition")
  void testFullMethodWithCondition() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .when(
                new ProbeCondition(
                    when(eq(ref("argStr"), value("foobar"))), "argStr == \"foobar\""))
            .captureSnapshot(true)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean snapshotReceived = new AtomicBoolean();
    registerSnapshotListener(
        snapshot -> {
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertFullMethodCaptureArgs(snapshot.getCaptures().getEntry());
          snapshotReceived.set(true);
        });
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(() -> snapshotReceived.get() && statusResult.get());
  }

  @Test
  @DisplayName("testFullMethodWithConditionAtExit")
  void testFullMethodWithConditionAtExit() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .when(
                new ProbeCondition(
                    when(and(gt(len(ref("@return")), value(0)), gt(ref("@duration"), value(0)))),
                    "len(@return) > 0 && @duration > 0"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean snapshotReceived = new AtomicBoolean();
    registerSnapshotListener(
        snapshot -> {
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
          snapshotReceived.set(true);
        });
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(() -> snapshotReceived.get() && statusResult.get());
  }

  @Test
  @DisplayName("testFullMethodWithConditionFailed")
  void testFullMethodWithConditionFailed() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .when(new ProbeCondition(when(eq(ref("noarg"), nullValue())), "noarg == null"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean snapshotReceived = new AtomicBoolean();
    registerSnapshotListener(
        snapshot -> {
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertEquals(1, snapshot.getEvaluationErrors().size());
          assertEquals(
              "Cannot find symbol: noarg", snapshot.getEvaluationErrors().get(0).getMessage());
          snapshotReceived.set(true);
        });
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(() -> snapshotReceived.get() && statusResult.get());
  }

  @Test
  @DisplayName("testFullMethodWithLogTemplate")
  void testFullMethodWithLogTemplate() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String LOG_TEMPLATE = "log line {argInt} {argStr} {argDouble} {argMap} {argVar}";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean snapshotReceived = new AtomicBoolean();
    AtomicBoolean correctLogMessage = new AtomicBoolean();
    registerIntakeRequestListener(
        intakeRequest -> {
          assertEquals(
              "log line 42 foobar 3.42 {[key1=val1], [key2=val2], [key3=val3]} [var1, var2, var3]",
              intakeRequest.getMessage());
          correctLogMessage.set(true);
        });
    registerSnapshotListener(
        snapshot -> {
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          snapshotReceived.set(true);
        });
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(() -> snapshotReceived.get() && correctLogMessage.get() && statusResult.get());
  }

  @Test
  @DisplayName("testMultiProbes")
  void testMultiProbes() throws Exception {
    final String METHOD_NAME = "fullMethod";
    List<LogProbe> probes = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      probes.add(
          LogProbe.builder()
              .probeId(getProbeId(i))
              .where(MAIN_CLASS_NAME, METHOD_NAME)
              .captureSnapshot(true)
              .build());
    }
    setCurrentConfiguration(createConfig(probes));
    final int NB_PROBES = 10;
    int expectedSnapshotUploads = NB_PROBES * 4; // (3 statuses + 1 snapshot) * 10 probes
    targetProcess =
        createProcessBuilder(logFilePath, METHOD_NAME, String.valueOf(expectedSnapshotUploads))
            .start();
    Set<String> probeIds = new HashSet<>();
    AtomicBoolean allSnapshotReceived = new AtomicBoolean();
    AtomicBoolean allStatusEmitting = new AtomicBoolean();
    registerSnapshotListener(
        snapshot -> {
          probeIds.add(snapshot.getProbe().getId());
          allSnapshotReceived.set(probeIds.size() == NB_PROBES);
        });
    Map<String, ProbeStatus.Status> statuses = new HashMap<>();
    registerProbeStatusListener(
        probeStatus -> {
          statuses.put(
              probeStatus.getDiagnostics().getProbeId().getId(),
              probeStatus.getDiagnostics().getStatus());
          allStatusEmitting.set(
              statuses.size() == NB_PROBES
                  && statuses.values().stream()
                      .allMatch(status -> status == ProbeStatus.Status.EMITTING));
        });
    processRequests(() -> allSnapshotReceived.get() && allStatusEmitting.get());
    assertEquals(NB_PROBES, probeIds.size());
    for (int i = 0; i < NB_PROBES; i++) {
      assertTrue(probeIds.contains(String.valueOf(i)));
    }
    assertFalse(logHasErrors(logFilePath, it -> false));
  }

  @Test
  @DisplayName("testSamplingSnapshotDefault")
  @DisabledIf(value = "datadog.trace.api.Platform#isJ9", disabledReason = "Flaky on J9 JVMs")
  void testSamplingSnapshotDefault() throws Exception {
    doSamplingSnapshot(null, MethodLocation.EXIT);
  }

  @Test
  @DisplayName("testSamplingSnapshotDefaultWithConditionAtEntry")
  @DisabledIf(value = "datadog.trace.api.Platform#isJ9", disabledReason = "Flaky on J9 JVMs")
  void testSamplingSnapshotDefaultWithConditionAtEntry() throws Exception {
    doSamplingSnapshot(
        new ProbeCondition(DSL.when(DSL.eq(value(1), value(1))), "1 == 1"), MethodLocation.ENTRY);
  }

  @Test
  @DisplayName("testSamplingSnapshotDefaultWithConditionAtExit")
  @DisabledIf(value = "datadog.trace.api.Platform#isJ9", disabledReason = "Flaky on J9 JVMs")
  void testSamplingSnapshotDefaultWithConditionAtExit() throws Exception {
    doSamplingSnapshot(
        new ProbeCondition(DSL.when(DSL.eq(value(1), value(1))), "1 == 1"), MethodLocation.EXIT);
  }

  private void doSamplingSnapshot(ProbeCondition probeCondition, MethodLocation evaluateAt)
      throws Exception {
    final int LOOP_COUNT = 1000;
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, "fullMethod")
            .when(probeCondition)
            .evaluateAt(evaluateAt)
            .captureSnapshot(true)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess =
        createProcessBuilder(
                logFilePath, "loopingFullMethod", EXPECTED_UPLOADS, String.valueOf(LOOP_COUNT))
            .start();
    int count = countSnapshots();
    assertTrue(count >= 2 && count <= 20, "snapshots=" + count);
  }

  @Test
  @DisplayName("testSamplingLogDefault")
  @DisabledIf(value = "datadog.trace.api.Platform#isJ9", disabledReason = "Flaky on J9 JVMs")
  void testSamplingLogDefault() throws Exception {
    batchSize = 100;
    final int LOOP_COUNT = 1000;
    final String LOG_TEMPLATE = "log line {argInt} {argStr} {argDouble} {argMap} {argVar}";
    final String EXPECTED_UPLOADS = String.valueOf(LOOP_COUNT / batchSize + 3); // +3 statuses
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, "fullMethod")
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess =
        createProcessBuilder(
                logFilePath, "loopingFullMethod", EXPECTED_UPLOADS, String.valueOf(LOOP_COUNT))
            .start();
    int count = countSnapshots();
    assertTrue(count >= 850 && count <= 1000, "logs=" + count);
  }

  @Test
  @DisplayName("testSamplingLogCustom")
  @DisabledIf(value = "datadog.trace.api.Platform#isJ9", disabledReason = "Flaky on J9 JVMs")
  void testSamplingLogCustom() throws Exception {
    final int LOOP_COUNT = 1000;
    final String LOG_TEMPLATE = "log line {argInt} {argStr} {argDouble} {argMap} {argVar}";
    final String EXPECTED_UPLOADS = "170";
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, "fullMethod")
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .evaluateAt(MethodLocation.EXIT)
            .sampling(10)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess =
        createProcessBuilder(
                logFilePath, "loopingFullMethod", EXPECTED_UPLOADS, String.valueOf(LOOP_COUNT))
            .start();
    assertTrue(countSnapshots() < 200);
  }

  @Test
  @DisplayName("testUncaughtException")
  void testUncaughtException() throws Exception {
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    final String METHOD_NAME = "exceptionMethod";
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(true)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess =
        createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS, "uncaught").start();
    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertFalse(logHasErrors(logFilePath, it -> false));
    String bodyStr = request.getBody().readUtf8();
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
    System.out.println(bodyStr);
    JsonSnapshotSerializer.IntakeRequest intakeRequest = adapter.fromJson(bodyStr).get(0);
    Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
    assertEquals("123356536", snapshot.getProbe().getId());
    CapturedContext.CapturedThrowable throwable =
        snapshot.getCaptures().getReturn().getCapturedThrowable();
    assertEquals("oops uncaught!", throwable.getMessage());
    assertTrue(throwable.getStacktrace().size() > 0);
    assertEquals(
        "datadog.smoketest.debugger.Main.exceptionMethod",
        throwable.getStacktrace().get(0).getFunction());
  }

  @Test
  @DisplayName("testCaughtException")
  void testCaughtException() throws Exception {
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 snapshot
    final String METHOD_NAME = "exceptionMethod";
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(true)
            .build();
    setCurrentConfiguration(createConfig(probe));
    targetProcess =
        createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS, "caught").start();
    RecordedRequest request = retrieveSnapshotRequest();
    assertNotNull(request);
    assertFalse(logHasErrors(logFilePath, it -> false));
    String bodyStr = request.getBody().readUtf8();
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter = createAdapterForSnapshot();
    System.out.println(bodyStr);
    JsonSnapshotSerializer.IntakeRequest intakeRequest = adapter.fromJson(bodyStr).get(0);
    Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
    assertEquals("123356536", snapshot.getProbe().getId());
    assertEquals(1, snapshot.getCaptures().getCaughtExceptions().size());
    CapturedContext.CapturedThrowable throwable =
        snapshot.getCaptures().getCaughtExceptions().get(0);
    assertEquals("oops caught!", throwable.getMessage());
    assertEquals(
        "datadog.smoketest.debugger.Main.exceptionMethod",
        throwable.getStacktrace().get(0).getFunction());
  }

  private int countSnapshots() throws Exception {
    int snapshotCount = 0;
    RecordedRequest request;
    do {
      request = retrieveSnapshotRequest();
      if (request != null) {
        String bodyStr = request.getBody().readUtf8();
        JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter =
            createAdapterForSnapshot();
        List<JsonSnapshotSerializer.IntakeRequest> intakeRequests = adapter.fromJson(bodyStr);
        long count =
            intakeRequests.stream()
                .map(intakeRequest -> intakeRequest.getDebugger().getSnapshot())
                .count();
        snapshotCount += count;
      }
    } while (request != null);
    LOG.info("snapshots={}", snapshotCount);
    return snapshotCount;
  }

  private ProbeId getProbeId(int i) {
    return new ProbeId(String.valueOf(i), 0);
  }

  private void assertFullMethodCaptureArgs(CapturedContext context) {
    if (Platform.isJ9()) {
      // skip for J9/OpenJ9 as we cannot get local variable debug info.
      return;
    }
    assertCaptureArgs(context, "argInt", "int", "42");
    assertCaptureArgs(context, "argStr", "java.lang.String", "foobar");
    assertCaptureArgs(context, "argDouble", "double", "3.42");
    assertCaptureArgs(context, "argMap", "java.util.HashMap", "{key1=val1, key2=val2, key3=val3}");
    assertCaptureArgs(context, "argVar", "java.lang.String[]", "[var1, var2, var3]");
  }
}
