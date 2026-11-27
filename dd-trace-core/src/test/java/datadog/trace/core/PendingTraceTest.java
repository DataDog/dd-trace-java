package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
            emptyMap(),
            false,
            "",
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
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan span = createSimpleSpan(trace);
    trace.registerSpan(span);

    verify(healthMetrics, times(1)).onCreateSpan(span);

    span.finish();

    verify(healthMetrics, times(1)).onCreateTrace();
  }

  @Test
  void writingRunningSpansDoesNotCallOnFinishSpan() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());
    when(buffer.longRunningSpansEnabled()).thenReturn(true);

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan root = createSimpleSpanWithID(trace, 1);
    trace.registerSpan(root);
    DDSpan child = createSimpleSpanWithID(trace, 2);
    trace.registerSpan(child);
    child.finish();
    clearInvocations(healthMetrics);

    // write the running root, as the long-running tracker does periodically
    trace.setLongRunningTrackedState(LongRunningTracesTracker.WRITE_RUNNING_SPANS);
    trace.write();

    ArgumentCaptor<SpanList> written = ArgumentCaptor.forClass(SpanList.class);
    verify(stubTracer, atLeastOnce()).write(written.capture());
    assertTrue(
        written.getAllValues().stream().anyMatch(spans -> spans.contains(root)),
        "the running root should have been written");
    verify(healthMetrics, never()).onCreateSpan(any());
    verify(healthMetrics, never()).onEndSpan();
    verify(healthMetrics, never()).onFinishSpan();

    root.finish();

    verify(healthMetrics, never()).onCreateSpan(any());
    verify(healthMetrics, times(1)).onEndSpan();
    verify(healthMetrics, times(1)).onFinishSpan();
  }

  @Test
  void phasedFinishCallsOnEndSpanBeforePublishCallsOnFinishSpan() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            mock(PendingTraceBuffer.class),
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan span = createSimpleSpan(trace);
    trace.registerSpan(span);

    assertTrue(span.phasedFinish());
    assertFalse(span.phasedFinish());

    verify(healthMetrics, times(1)).onEndSpan();
    verify(healthMetrics, never()).onFinishSpan();

    span.publish();
    span.publish();

    verify(healthMetrics, times(1)).onEndSpan();
    verify(healthMetrics, times(1)).onFinishSpan();
  }

  @Test
  void writeWhenRunningSpansDisabledOnlyCompletedSpansWritten() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());
    when(buffer.longRunningSpansEnabled()).thenReturn(true);

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

    DDSpan span1 = createSimpleSpanWithID(trace, 39);
    span1.setDurationNano(31);
    span1.setSamplingPriority(USER_KEEP);
    trace.registerSpan(span1);

    DDSpan unfinishedSpan = createSimpleSpanWithID(trace, 191);
    trace.registerSpan(unfinishedSpan);

    DDSpan span2 = createSimpleSpanWithID(trace, 9999);
    span2.setDurationNano(9191);
    trace.registerSpan(span2);

    List<DDSpan> traceToWrite = new ArrayList<>(0);
    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, false);

    assertEquals(2, completedSpans);
    assertEquals(2, traceToWrite.size());
    assertTrue(traceToWrite.containsAll(Arrays.asList(span1, span2)));
    assertEquals(1, trace.getSpans().size());
    assertEquals(unfinishedSpan, trace.getSpans().iterator().next());
  }

  @Test
  void writeWhenRunningSpansEnabledCompleteAndRunningSpansWritten() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);

    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());
    when(buffer.longRunningSpansEnabled()).thenReturn(true);

    PendingTrace trace =
        new PendingTrace(
            stubTracer,
            DDTraceId.from(0),
            buffer,
            mock(TimeSource.class),
            null,
            false,
            healthMetrics);

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

    List<DDSpan> traceToWrite = new ArrayList<>(0);
    int completedSpans = trace.enqueueSpansToWrite(traceToWrite, true);

    assertEquals(2, completedSpans);
    assertEquals(4, traceToWrite.size());
    assertTrue(
        traceToWrite.containsAll(Arrays.asList(span1, span2, unfinishedSpan, unfinishedSpan2)));
    assertEquals(2, trace.getSpans().size());
    assertTrue(
        new ArrayList<>(trace.getSpans())
            .containsAll(Arrays.asList(unfinishedSpan, unfinishedSpan2)));
  }

  private PendingTrace defaultModeTrace(CoreTracer stubTracer) {
    CoreTracer.ConfigSnapshot traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    PendingTraceBuffer buffer = mock(PendingTraceBuffer.class);
    when(stubTracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(emptyMap());
    when(buffer.longRunningSpansEnabled()).thenReturn(false);
    return new PendingTrace(
        stubTracer,
        DDTraceId.from(0),
        buffer,
        mock(TimeSource.class),
        null,
        false,
        mock(HealthMetrics.class));
  }

  @Test
  void finishedSpansWrittenMostRecentFirstWithLinksCleared() {
    CoreTracer stubTracer = mock(CoreTracer.class);
    PendingTrace trace = defaultModeTrace(stubTracer);
    DDSpan root = createSimpleSpanWithID(trace, 1);
    trace.registerSpan(root);
    DDSpan child1 = createSimpleSpanWithID(trace, 2);
    trace.registerSpan(child1);
    DDSpan child2 = createSimpleSpanWithID(trace, 3);
    trace.registerSpan(child2);

    assertEquals(TraceCollector.PublishState.PENDING, trace.onPublish(child1));
    assertEquals(TraceCollector.PublishState.PENDING, trace.onPublish(child2));
    assertEquals(Arrays.asList(child2, child1), new ArrayList<>(trace.getSpans()));

    assertEquals(TraceCollector.PublishState.WRITTEN, trace.onPublish(root));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<SpanList> written = ArgumentCaptor.forClass(SpanList.class);
    verify(stubTracer).write(written.capture());
    assertEquals(Arrays.asList(root, child2, child1), written.getValue());
    // a written span the trace keeps (the root) must not keep the other written spans alive
    assertEquals(null, root.nextFinished);
    assertEquals(null, child2.nextFinished);
    assertEquals(null, child1.nextFinished);
    assertTrue(trace.getSpans().isEmpty());
  }

  @Test
  void finishedSpansReadInPlace() {
    PendingTrace trace = defaultModeTrace(mock(CoreTracer.class));
    DDSpan root = createSimpleSpanWithID(trace, 1);
    trace.registerSpan(root);
    assertEquals(null, trace.evaluateSamplingPriority());
    assertEquals(Long.MAX_VALUE, trace.oldestFinishedTime());

    DDSpan early = createSimpleSpanWithID(trace, 2);
    early.setDurationNano(10);
    trace.registerSpan(early);
    DDSpan late = createSimpleSpanWithID(trace, 3);
    late.setDurationNano(500);
    late.setSamplingPriority(USER_KEEP);
    trace.registerSpan(late);
    trace.onPublish(early);
    trace.onPublish(late);

    // the most recently finished span decides, as the deque's head did
    assertEquals(USER_KEEP, trace.evaluateSamplingPriority());
    assertEquals(10, trace.oldestFinishedTime());
  }

  @Test
  void concurrentlyFinishedSpansEachWrittenOnce() throws Exception {
    PendingTrace trace = defaultModeTrace(mock(CoreTracer.class));
    DDSpan root = createSimpleSpanWithID(trace, 1);
    trace.registerSpan(root);
    int threads = 4;
    int perThread = 2_000;
    List<DDSpan> all = new ArrayList<>();
    for (int i = 0; i < threads * perThread; i++) {
      DDSpan span = createSimpleSpanWithID(trace, 2 + i);
      trace.registerSpan(span);
      all.add(span);
    }

    CountDownLatch start = new CountDownLatch(1);
    List<Thread> finishers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      List<DDSpan> mine = all.subList(t * perThread, (t + 1) * perThread);
      Thread thread =
          new Thread(() -> {
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            for (DDSpan span : mine) {
              trace.onPublish(span);
            }
          });
      thread.start();
      finishers.add(thread);
    }
    List<DDSpan> drained = new ArrayList<>();
    start.countDown();
    // drain while the spans are being finished, then once more after
    while (finishers.stream().anyMatch(Thread::isAlive)) {
      trace.enqueueSpansToWrite(drained, false);
    }
    for (Thread thread : finishers) {
      thread.join();
    }
    trace.enqueueSpansToWrite(drained, false);

    assertEquals(all.size(), drained.size());
    assertEquals(new HashSet<>(all), new HashSet<>(drained));
  }
}
