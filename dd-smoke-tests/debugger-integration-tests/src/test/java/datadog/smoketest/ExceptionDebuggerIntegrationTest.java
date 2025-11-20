package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.sink.Snapshot;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.util.Flaky;
import datadog.trace.test.util.NonRetryable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

@NonRetryable
@Flaky
public class ExceptionDebuggerIntegrationTest extends ServerAppDebuggerIntegrationTest {

  private List<String> snapshotIdTags = new ArrayList<>();
  private boolean traceReceived;
  private boolean snapshotReceived;
  private Map<String, Snapshot> snapshots = new HashMap<>();
  private List<String> additionalJvmArgs = new ArrayList<>();
  private Supplier<String> timeoutMessage =
      () ->
          String.format(
              "Timeout! traceReceived=%s snapshotReceived=%s #snapshots=%d",
              traceReceived, snapshotReceived, snapshots.size());

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    commandParams.add("-Ddd.exception.replay.enabled=true"); // enable exception replay
    commandParams.add("-Ddd.internal.exception.replay.only.local.root=false"); // for all spans
    commandParams.add("-Ddd.third.party.excludes=datadog.smoketest");
    // disable DI to make sure exception debugger works alone
    commandParams.remove("-Ddd.dynamic.instrumentation.enabled=true");
    commandParams.addAll(additionalJvmArgs);
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testSimpleSingleFrameException")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void testSimpleSingleFrameException() throws Exception {
    appUrl = startAppAndAndGetUrl();
    execute(appUrl, TRACED_METHOD_NAME, "oops"); // instrumenting first exception
    waitForInstrumentation(appUrl, SERVER_DEBUGGER_TEST_APP_CLASS, false);
    execute(appUrl, TRACED_METHOD_NAME, "oops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    registerIntakeRequestListener(
        intakeRequest -> {
          assertEquals("snapshot", intakeRequest.getType());
        });
    processRequests(
        () -> {
          if (snapshotIdTags.isEmpty()) {
            return false;
          }
          String snapshotId0 = snapshotIdTags.get(0);
          if (traceReceived && snapshotReceived && snapshots.containsKey(snapshotId0)) {
            Snapshot snapshot = snapshots.get(snapshotId0);
            assertNotNull(snapshot);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            return true;
          }
          return false;
        },
        timeoutMessage);
  }

  @Test
  @DisplayName("testNoSubsequentCaptureAfterFirst")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void testNoSubsequentCaptureAfterFirst() throws Exception {
    appUrl = startAppAndAndGetUrl();
    testSimpleSingleFrameException();
    resetSnapshotsAndTraces();
    // we should not receive any more snapshots after the first one
    execute(appUrl, TRACED_METHOD_NAME, "oops"); // no snapshot should be sent
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              assertFalse(span.getMeta().containsKey("error.debug_info_captured"));
              assertFalse(span.getMeta().containsKey("_dd.debug.error.0.snapshot_id"));
              traceReceived = true;
            }
          }
        });
    processRequests(() -> traceReceived && !snapshotReceived, timeoutMessage);
  }

  // DeepOops exception stacktrace:
  // java.lang.RuntimeException: oops
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithException(ServerDebuggerTestApplication.java:190)
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException5(ServerDebuggerTestApplication.java:210)
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException4(ServerDebuggerTestApplication.java:206)
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException3(ServerDebuggerTestApplication.java:202)
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException2(ServerDebuggerTestApplication.java:198)
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException1(ServerDebuggerTestApplication.java:194)
  // datadog.smoketest.debugger.ServerDebuggerTestApplication.runTracedMethod(ServerDebuggerTestApplication.java:140)
  @Test
  @DisplayName("test3CapturedFrames")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void test3CapturedFrames() throws Exception {
    appUrl = startAppAndAndGetUrl();
    execute(appUrl, TRACED_METHOD_NAME, "deepOops"); // instrumenting first exception
    waitForInstrumentation(appUrl, SERVER_DEBUGGER_TEST_APP_CLASS, false);
    execute(appUrl, TRACED_METHOD_NAME, "deepOops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    processRequests(
        () -> {
          if (snapshotIdTags.isEmpty()) {
            return false;
          }
          String snapshotId0 = snapshotIdTags.get(0);
          String snapshotId1 = snapshotIdTags.get(1);
          String snapshotId2 = snapshotIdTags.get(2);
          if (traceReceived
              && snapshotReceived
              && snapshots.containsKey(snapshotId0)
              && snapshots.containsKey(snapshotId1)
              && snapshots.containsKey(snapshotId2)) {
            // snapshot 0
            Snapshot snapshot = snapshots.get(snapshotId0);
            assertNotNull(snapshot);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithException",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            // snapshot 1
            snapshot = snapshots.get(snapshotId1);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException5",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            // snapshot 2
            snapshot = snapshots.get(snapshotId2);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException4",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            return true;
          }
          return false;
        },
        timeoutMessage);
  }

  @Test
  @DisplayName("test5CapturedFrames")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void test5CapturedFrames() throws Exception {
    additionalJvmArgs.add("-Ddd.exception.replay.capture.max.frames=5");
    appUrl = startAppAndAndGetUrl();
    execute(appUrl, TRACED_METHOD_NAME, "deepOops"); // instrumenting first exception
    waitForInstrumentation(appUrl, SERVER_DEBUGGER_TEST_APP_CLASS, false);
    execute(appUrl, TRACED_METHOD_NAME, "deepOops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    processRequests(
        () -> {
          if (snapshotIdTags.isEmpty()) {
            return false;
          }
          String snapshotId0 = snapshotIdTags.get(0);
          String snapshotId1 = snapshotIdTags.get(1);
          String snapshotId2 = snapshotIdTags.get(2);
          String snapshotId3 = snapshotIdTags.get(3);
          String snapshotId4 = snapshotIdTags.get(4);
          if (traceReceived
              && snapshotReceived
              && snapshots.containsKey(snapshotId0)
              && snapshots.containsKey(snapshotId1)
              && snapshots.containsKey(snapshotId2)
              && snapshots.containsKey(snapshotId3)
              && snapshots.containsKey(snapshotId4)) {
            // snapshot 0
            Snapshot snapshot = snapshots.get(snapshotId0);
            assertNotNull(snapshot);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithException",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            // snapshot 1
            snapshot = snapshots.get(snapshotId1);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException5",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            // snapshot 2
            snapshot = snapshots.get(snapshotId2);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException4",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            // snapshot 3
            snapshot = snapshots.get(snapshotId3);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException3",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            // snapshot 4
            snapshot = snapshots.get(snapshotId4);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException2",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            return true;
          }
          return false;
        },
        timeoutMessage);
  }

  @Test
  @DisplayName("test3CapturedRecursiveFrames")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void test3CapturedRecursiveFrames() throws Exception {
    appUrl = startAppAndAndGetUrl();
    execute(appUrl, TRACED_METHOD_NAME, "recursiveOops"); // instrumenting first exception
    waitForInstrumentation(appUrl, SERVER_DEBUGGER_TEST_APP_CLASS, false);
    execute(appUrl, TRACED_METHOD_NAME, "recursiveOops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    processRequests(
        () -> {
          if (snapshotIdTags.isEmpty()) {
            return false;
          }
          if (traceReceived
              && snapshotReceived
              && snapshots.containsKey(snapshotIdTags.get(0))
              && snapshots.containsKey(snapshotIdTags.get(1))
              && snapshots.containsKey(snapshotIdTags.get(2))) {
            assertEquals(3, snapshotIdTags.size());
            assertEquals(3, snapshots.size());
            // snapshot 0
            assertRecursiveSnapshot(snapshots.get(snapshotIdTags.get(0)));
            // snapshot 1
            assertRecursiveSnapshot(snapshots.get(snapshotIdTags.get(1)));
            // snapshot 2
            assertRecursiveSnapshot(snapshots.get(snapshotIdTags.get(2)));
            return true;
          }
          return false;
        },
        timeoutMessage);
  }

  private static void assertRecursiveSnapshot(Snapshot snapshot) {
    assertNotNull(snapshot);
    assertEquals(
        "recursiveOops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
    assertEquals(
        "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithRecursiveException",
        snapshot.getStack().get(0).getFunction());
  }

  @Test
  @DisplayName("testLambdaHiddenFrames")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "HotSpot specific test")
  void testLambdaHiddenFrames() throws Exception {
    additionalJvmArgs.add("-XX:+UnlockDiagnosticVMOptions");
    additionalJvmArgs.add("-XX:+ShowHiddenFrames");
    appUrl = startAppAndAndGetUrl();
    execute(appUrl, TRACED_METHOD_NAME, "lambdaOops"); // instrumenting first exception
    waitForInstrumentation(appUrl, SERVER_DEBUGGER_TEST_APP_CLASS, false);
    execute(appUrl, TRACED_METHOD_NAME, "lambdaOops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    processRequests(
        () -> {
          if (snapshotIdTags.isEmpty()) {
            return false;
          }
          String snapshotId0 = snapshotIdTags.get(0);
          if (traceReceived && snapshotReceived && snapshots.containsKey(snapshotId0)) {
            Snapshot snapshot = snapshots.get(snapshotId0);
            assertNotNull(snapshot);
            assertEquals(
                "lambdaOops",
                snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertEquals(
                "datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithLambdaException",
                snapshot.getStack().get(0).getFunction());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            return true;
          }
          return false;
        },
        timeoutMessage);
  }

  private void resetSnapshotsAndTraces() {
    resetTraceListener();
    traceReceived = false;
    snapshotReceived = false;
    snapshots.clear();
    snapshotIdTags.clear();
  }

  private void assertFullMethodCaptureArgs(CapturedContext context) {
    if (JavaVirtualMachine.isJ9()) {
      // skip for J9/OpenJ9 as we cannot get local variable debug info.
      return;
    }
    assertCaptureArgs(context, "argInt", "int", "42");
    assertCaptureArgs(context, "argStr", "java.lang.String", "foobar");
    assertCaptureArgs(context, "argDouble", "double", "3.42");
    assertCaptureArgs(context, "argMap", "java.util.HashMap", "{key1=val1, key2=val2, key3=val3}");
    assertCaptureArgs(context, "argVar", "java.lang.String[]", "[var1, var2, var3]");
  }

  private void receiveExceptionReplayTrace(DecodedTrace decodedTrace) {
    for (DecodedSpan span : decodedTrace.getSpans()) {
      if (isTracedFullMethodSpan(span) && span.getMeta().containsKey("error.debug_info_captured")) {
        // assert that we have received the trace with ER tags only once
        assertTrue(snapshotIdTags.isEmpty());
        for (int i = 0; i < 5; i++) {
          String snapshotId = span.getMeta().get("_dd.debug.error." + i + ".snapshot_id");
          if (snapshotId != null) {
            snapshotIdTags.add(snapshotId);
          }
        }
        assertFalse(traceReceived);
        traceReceived = true;
      }
    }
  }

  private void receiveSnapshot(Snapshot snapshot) {
    snapshots.put(snapshot.getId(), snapshot);
    snapshotReceived = true;
  }
}
