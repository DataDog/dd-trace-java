package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DatadogProfilingIntegrationTest {
  @Test
  void rebindsSpanContextAndClearsRootContext() {
    DatadogProfiler profiler = mock(DatadogProfiler.class);
    when(profiler.operationNameOffset()).thenReturn(5);
    when(profiler.resourceNameOffset()).thenReturn(6);

    try (MockedStatic<DatadogProfiler> profilerFactory = mockStatic(DatadogProfiler.class)) {
      profilerFactory.when(DatadogProfiler::newInstance).thenReturn(profiler);

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
      verify(profiler).setTraceContext(11L, 22L, 33L, 44L, 5, "operation", 6, "resource");

      integration.setContext(Context.root());
      verify(profiler).clearTraceContext();
    }
  }
}
