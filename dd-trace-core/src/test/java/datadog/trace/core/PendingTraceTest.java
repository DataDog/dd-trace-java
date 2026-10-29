package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.TimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

@Timeout(60)
public class PendingTraceTest extends PendingTraceTestBase {

  @BeforeAll
  static void checkJvm() {
    Assumptions.assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to"
            + " correctly extract method parameters without args, when the code is compiled on a"
            + " later JDK (targeting 8). This can manifest when creating mocks.");
  }

  @Override
  protected boolean useStrictTraceWrites() {
    // This tests the behavior of the relaxed pending trace implementation
    return false;
  }

  /** A trace over a stub tracer and a mock buffer, with or without long-running traces. */
  private static PendingTrace newTrace(
      CoreTracer stubTracer, boolean longRunning, HealthMetrics healthMetrics) {
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    when(buffer.longRunningSpansEnabled()).thenReturn(longRunning);
    return new PendingTrace(
        stubTracer, DDTraceId.from(0), buffer, mock(TimeSource.class), null, false, healthMetrics);
  }

  private DDSpan createSimpleSpan(PendingTrace trace) {
    return createSimpleSpanWithID(trace, 1);
  }

  private DDSpan createSimpleSpanWithID(PendingTrace trace, long id) {
    return new DDSpan(
        "test",
        0L,
        new DDSpanContext(
            DDTraceId.from(1),
            id,
            0,
            null,
            "",
            "",
            "",
            UNSET,
            "",
            Collections.emptyMap(),
            false,
            "",
            0,
            trace,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty()),
        null);
  }

  @Test
  void traceStillReportedWhenUnfinishedContinuationDiscarded()
      throws InterruptedException, TimeoutException {
    ContextScope scope = tracer.activateSpan(rootSpan);
    tracer.capture(rootSpan);
    scope.close();

    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(rootSpan), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());
    // root span buffer delay expires
    writer.waitForTraces(1);

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(rootSpan)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
  }

  @Test
  void verifyHealthMetricsCalled() {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    PendingTrace trace = newTrace(mock(CoreTracer.class), false, healthMetrics);

    DDSpan span = createSimpleSpan(trace);
    trace.registerSpan(span);

    verify(healthMetrics, times(1)).onCreateSpan();

    span.finish();

    verify(healthMetrics, times(1)).onCreateTrace();
  }

  @Test
  void writeWhenRunningSpansDisabledOnlyCompletedSpansWritten() {
    PendingTrace trace = newTrace(mock(CoreTracer.class), true, mock(HealthMetrics.class));

    DDSpan span1 = createSimpleSpanWithID(trace, 39);
    span1.setDurationNano(31);
    span1.setSamplingPriority(USER_KEEP);
    trace.registerSpan(span1);

    DDSpan unfinishedSpan = createSimpleSpanWithID(trace, 191);
    trace.registerSpan(unfinishedSpan);

    DDSpan span2 = createSimpleSpanWithID(trace, 9999);
    span2.setDurationNano(9191);
    trace.registerSpan(span2);

    // finished spans reach the write through onPublish, once every span is registered
    trace.onPublish(span1);
    trace.onPublish(span2);

    List<DDSpan> traceToWrite = new ArrayList<>(0);
    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, false);

    assertEquals(2, completedSpans);
    assertEquals(2, traceToWrite.size());
    assertTrue(traceToWrite.containsAll(Arrays.asList(span1, span2)));
    assertTrue(trace.getSpans().isEmpty());
    assertEquals(1, trace.getRunningSpans().size());
    assertEquals(unfinishedSpan, trace.getRunningSpans().iterator().next());
  }

  @Test
  void writeWhenRunningSpansEnabledCompleteAndRunningSpansWritten() {
    PendingTrace trace = newTrace(mock(CoreTracer.class), true, mock(HealthMetrics.class));

    DDSpan span1 = createSimpleSpanWithID(trace, 39);
    span1.setDurationNano(31);
    span1.setSamplingPriority(USER_KEEP);
    trace.registerSpan(span1);

    DDSpan unfinishedSpan = createSimpleSpanWithID(trace, 191);
    trace.registerSpan(unfinishedSpan);

    DDSpan span2 = createSimpleSpanWithID(trace, 9999);
    span2.setServiceName("9191");
    span2.setDurationNano(9191);
    trace.registerSpan(span2);

    DDSpan unfinishedSpan2 = createSimpleSpanWithID(trace, 77771);
    trace.registerSpan(unfinishedSpan2);

    // finished spans reach the write through onPublish, once every span is registered
    trace.onPublish(span1);
    trace.onPublish(span2);

    List<DDSpan> traceToWrite = new ArrayList<>(0);
    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, true);

    assertEquals(2, completedSpans);
    assertEquals(4, traceToWrite.size());
    assertTrue(
        traceToWrite.containsAll(Arrays.asList(span1, span2, unfinishedSpan, unfinishedSpan2)));
    assertTrue(trace.getSpans().isEmpty());
    assertEquals(2, trace.getRunningSpans().size());
    assertTrue(
        new ArrayList<>(trace.getRunningSpans())
            .containsAll(Arrays.asList(unfinishedSpan, unfinishedSpan2)));
  }

  @Test
  void runningSpanWrittenAgainOnceFinishedAndFinishedSpansNotRepeated() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    PendingTrace trace = newTrace(stubTracer, true, mock(HealthMetrics.class));

    DDSpan root = createSimpleSpanWithID(trace, 1);
    trace.registerSpan(root);
    DDSpan child = createSimpleSpanWithID(trace, 2);
    trace.registerSpan(child);
    child.setDurationNano(5);
    trace.onPublish(child);

    // periodic long-running write: the finished child, and the root as running
    trace.setLongRunningTrackedState(LongRunningTracesTracker.WRITE_RUNNING_SPANS);
    trace.write();

    root.setDurationNano(10);
    trace.onPublish(root);
    trace.write();

    ArgumentCaptor<SpanList> written = ArgumentCaptor.forClass(SpanList.class);
    verify(stubTracer, times(2)).write(written.capture());
    List<SpanList> writes = written.getAllValues();
    assertEquals(2, writes.get(0).size());
    assertTrue(writes.get(0).containsAll(Arrays.asList(child, root)));
    assertEquals(Collections.singletonList(root), new ArrayList<>(writes.get(1)));
    assertTrue(trace.getSpans().isEmpty());
    assertTrue(trace.getRunningSpans().isEmpty());
  }

  @Test
  void noRunningSpansListWithoutLongRunningTraces() {
    PendingTrace trace = newTrace(mock(CoreTracer.class), false, mock(HealthMetrics.class));

    assertNull(trace.getRunningSpans());
  }
}
