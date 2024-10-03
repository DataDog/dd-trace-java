package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

public class ExceptionDebuggerIntegrationTest extends ServerAppDebuggerIntegrationTest {

  private String snapshotId0;
  private String snapshotId1;
  private String snapshotId2;
  private boolean traceReceived;
  private boolean snapshotReceived;
  private Map<String, Snapshot> snapshots = new HashMap<>();

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    commandParams.add("-Ddd.exception.replay.enabled=true"); // enable exception replay
    commandParams.add("-Ddd.internal.exception.replay.only.local.root=false"); // for all spans
    commandParams.add("-Ddd.third.party.excludes=datadog.smoketest");
    // disable DI to make sure exception debugger works alone
    commandParams.remove("-Ddd.dynamic.instrumentation.enabled=true");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testSimpleSingleFrameException")
  @DisabledIf(
      value = "datadog.trace.api.Platform#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void testSimpleSingleFrameException() throws Exception {
    execute(appUrl, TRACED_METHOD_NAME, "oops"); // instrumenting first exception
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME, "oops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    processRequests(
        () -> {
          if (traceReceived && snapshotReceived && snapshots.containsKey(snapshotId0)) {
            Snapshot snapshot = snapshots.get(snapshotId0);
            assertNotNull(snapshot);
            assertEquals(
                "oops", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
            assertFullMethodCaptureArgs(snapshot.getCaptures().getReturn());
            return true;
          }
          return false;
        });
  }

  @Test
  @DisplayName("testNoSubsequentCaptureAfterFirst")
  @DisabledIf(
      value = "datadog.trace.api.Platform#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void testNoSubsequentCaptureAfterFirst() throws Exception {
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
    processRequests(() -> traceReceived && !snapshotReceived);
  }

  @Test
  @DisplayName("test3CapturedFrames")
  @DisabledIf(
      value = "datadog.trace.api.Platform#isJ9",
      disabledReason = "we cannot get local variable debug info")
  void test3CapturedFrames() throws Exception {
    execute(appUrl, TRACED_METHOD_NAME, "deepOops"); // instrumenting first exception
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME, "deepOops"); // collecting snapshots and sending them
    registerTraceListener(this::receiveExceptionReplayTrace);
    registerSnapshotListener(this::receiveSnapshot);
    processRequests(
        () -> {
          if (traceReceived
              && snapshotReceived
              && snapshots.containsKey(snapshotId0)
              && snapshots.containsKey(snapshotId1)
              && snapshots.containsKey(snapshotId2)) {
            // java.lang.RuntimeException: oops
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithException(ServerDebuggerTestApplication.java:190)
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException5(ServerDebuggerTestApplication.java:210)
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException4(ServerDebuggerTestApplication.java:206)
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException3(ServerDebuggerTestApplication.java:202)
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException2(ServerDebuggerTestApplication.java:198)
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.tracedMethodWithDeepException1(ServerDebuggerTestApplication.java:194)
            //   at
            // datadog.smoketest.debugger.ServerDebuggerTestApplication.runTracedMethod(ServerDebuggerTestApplication.java:140)
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
        });
  }

  private void resetSnapshotsAndTraces() {
    resetTraceListener();
    traceReceived = false;
    snapshotReceived = false;
    snapshots.clear();
    snapshotId0 = null;
    snapshotId1 = null;
    snapshotId2 = null;
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

  private void receiveExceptionReplayTrace(DecodedTrace decodedTrace) {
    for (DecodedSpan span : decodedTrace.getSpans()) {
      if (isTracedFullMethodSpan(span) && span.getMeta().containsKey("error.debug_info_captured")) {
        // assert that we have received the trace with ER tags only once
        assertNull(snapshotId0);
        snapshotId0 = span.getMeta().get("_dd.debug.error.0.snapshot_id");
        snapshotId1 = span.getMeta().get("_dd.debug.error.1.snapshot_id");
        snapshotId2 = span.getMeta().get("_dd.debug.error.2.snapshot_id");
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
