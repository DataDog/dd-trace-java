package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.context.Context;
import datadog.trace.api.profiling.Timer.TimerType;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DatadogProfilingIntegrationTest {
  /**
   * {@link DatadogProfilingIntegration} keeps the profiler of its first initialization in a static
   * field, so all the tests share the same mock.
   */
  private static final DatadogProfiler PROFILER = mock(DatadogProfiler.class);

  private MockedStatic<DatadogProfiler> profilerStatics;

  @BeforeEach
  void setup() {
    reset(PROFILER);
    when(PROFILER.operationNameOffset()).thenReturn(5);
    when(PROFILER.resourceNameOffset()).thenReturn(6);
    profilerStatics = mockStatic(DatadogProfiler.class);
    profilerStatics.when(DatadogProfiler::newInstance).thenReturn(PROFILER);
  }

  @AfterEach
  void tearDown() {
    profilerStatics.close();
  }

  @Test
  void rebindsSpanContextAndClearsRootContext() {
    AgentSpanContext spanContext =
        mock(AgentSpanContext.class, withSettings().extraInterfaces(ProfilerContext.class));
    ProfilerContext profilerContext = (ProfilerContext) spanContext;
    when(profilerContext.getRootSpanId()).thenReturn(11L);
    when(profilerContext.getSpanId()).thenReturn(22L);
    when(profilerContext.getTraceIdHigh()).thenReturn(33L);
    when(profilerContext.getTraceIdLow()).thenReturn(44L);
    when(profilerContext.getOperationName()).thenReturn("operation");
    when(profilerContext.getResourceName()).thenReturn("resource");

    AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
    when(span.spanContext()).thenReturn(spanContext);

    DatadogProfilingIntegration integration = new DatadogProfilingIntegration();
    assertTrue(integration.isThreadContextBindingRequired());

    integration.setContext(span);
    verify(PROFILER).setTraceContext(11L, 22L, 33L, 44L, 5, "operation", 6, "resource");

    integration.setContext(Context.root());
    verify(PROFILER).clearTraceContext();
  }

  @Test
  void skipsQueueTimingWhileNoRecordingIsRunning() {
    profilerStatics.when(DatadogProfiler::isRecordingActive).thenReturn(false);

    Timing timing = new DatadogProfilingIntegration().start(TimerType.QUEUEING);

    assertSame(Timing.NoOp.INSTANCE, timing);
    verify(PROFILER, never()).newQueueTimeTracker();
  }

  @Test
  void timesQueueingWhileARecordingIsRunning() {
    QueueTimeTracker tracker = mock(QueueTimeTracker.class);
    when(PROFILER.newQueueTimeTracker()).thenReturn(tracker);
    profilerStatics.when(DatadogProfiler::isRecordingActive).thenReturn(true);

    assertSame(tracker, new DatadogProfilingIntegration().start(TimerType.QUEUEING));
  }

  @Test
  void skipsEndpointsWhileNoRecordingIsRunning() {
    profilerStatics.when(DatadogProfiler::isRecordingActive).thenReturn(false);

    new DatadogProfilingIntegration().onRootSpanFinished(rootSpan(), null);

    verify(PROFILER, never()).recordTraceRoot(anyLong(), anyString(), anyString());
  }

  @Test
  void recordsEndpointsWhileARecordingIsRunning() {
    profilerStatics.when(DatadogProfiler::isRecordingActive).thenReturn(true);

    new DatadogProfilingIntegration().onRootSpanFinished(rootSpan(), null);

    verify(PROFILER).recordTraceRoot(77L, "GET /users", "servlet.request");
  }

  private static AgentSpan rootSpan() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getSpanId()).thenReturn(77L);
    when(span.getResourceName()).thenReturn("GET /users");
    when(span.getOperationName()).thenReturn("servlet.request");
    return span;
  }

  @Test
  void dropsQueueTimeOnceTheRecordingHasStopped() {
    DatadogProfiler profiler = mock(DatadogProfiler.class, CALLS_REAL_METHODS);
    long longAgo = System.currentTimeMillis() - 60_000;

    profilerStatics.when(DatadogProfiler::isRecordingActive).thenReturn(true);
    assertTrue(profiler.shouldRecordQueueTimeEvent(longAgo));

    profilerStatics.when(DatadogProfiler::isRecordingActive).thenReturn(false);
    assertFalse(profiler.shouldRecordQueueTimeEvent(longAgo));
  }
}
