package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QueueTimeTrackerTest {

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  @AfterEach
  void restoreTracer() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void contextStoreTaskUsesActivationNanoForConsumingOverride() {
    RecordingQueueTimeRecorder recorder = new RecordingQueueTimeRecorder();
    QueueTimeTracker tracker = new QueueTimeTracker(recorder, 123L, 111L);
    installActiveProfilerContext(111L, 222L, 42L);

    tracker.setTask((Runnable) () -> {});
    tracker.setScheduler(QueueTimeTrackerTest.class);
    tracker.setQueue(QueueTimeTrackerTest.class);
    tracker.setQueueLength(7);
    tracker.setActivationStartNano(42L);
    tracker.report();

    assertEquals(111L, recorder.submittingSpanId);
    assertEquals(222L, recorder.consumingSpanIdOverride);
  }

  @Test
  void contextStoreTaskWithoutActivationNanoKeepsBaseSpanId() {
    RecordingQueueTimeRecorder recorder = new RecordingQueueTimeRecorder();
    QueueTimeTracker tracker = new QueueTimeTracker(recorder, 123L, 111L);
    installActiveProfilerContext(111L, 222L, 42L);

    tracker.setTask((Runnable) () -> {});
    tracker.setScheduler(QueueTimeTrackerTest.class);
    tracker.setQueue(QueueTimeTrackerTest.class);
    tracker.setQueueLength(7);
    tracker.report();

    assertEquals(111L, recorder.submittingSpanId);
    assertEquals(0L, recorder.consumingSpanIdOverride);
  }

  private static void installActiveProfilerContext(
      long spanId, long syntheticSpanId, long activationStartNano) {
    TestSpanContext context = mock(TestSpanContext.class);
    when(context.getSpanId()).thenReturn(spanId);
    when(context.getSyntheticWorkSpanIdForActivation(activationStartNano))
        .thenReturn(syntheticSpanId);

    AgentSpan span = mock(AgentSpan.class);
    when(span.context()).thenReturn(context);

    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    AgentTracer.forceRegister(tracer);
  }

  private interface TestSpanContext extends AgentSpanContext, ProfilerContext {}

  private static final class RecordingQueueTimeRecorder
      implements QueueTimeTracker.QueueTimeRecorder {
    private long submittingSpanId;
    private long consumingSpanIdOverride;

    @Override
    public boolean shouldRecordQueueTimeEvent(long startMillis) {
      return true;
    }

    @Override
    public void recordQueueTimeEvent(
        long startTicks,
        Object task,
        Class<?> scheduler,
        Class<?> queueType,
        int queueLength,
        Thread origin,
        long submittingSpanId,
        long consumingSpanIdOverride) {
      this.submittingSpanId = submittingSpanId;
      this.consumingSpanIdOverride = consumingSpanIdOverride;
    }
  }
}
