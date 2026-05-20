package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DatadogProfilingIntegration#onSpanFinished(AgentSpan)}.
 *
 * <p>Because {@link DatadogProfiler} wraps a native library, we verify the filtering logic and
 * dispatch path without asserting on the native event itself. Native calls simply must not throw
 * (the {@code if (profiler != null)} guard inside {@link DatadogProfiler} protects them on systems
 * where the native library is unavailable).
 */
class DatadogProfilerSpanNodeTest {

  /**
   * When the span's context does NOT implement {@link ProfilerContext}, {@code onSpanFinished}
   * should be a no-op and must not throw.
   */
  @Test
  void onSpanFinished_nonProfilerContext_isNoOp() {
    DatadogProfilingIntegration integration = new DatadogProfilingIntegration();
    AgentSpan span = mock(AgentSpan.class);
    AgentSpanContext ctx = mock(AgentSpanContext.class); // plain context, NOT a ProfilerContext
    when(span.context()).thenReturn(ctx);

    assertDoesNotThrow(() -> integration.onSpanFinished(span));
  }

  /**
   * When the span's context DOES implement {@link ProfilerContext}, {@code onSpanFinished} extracts
   * fields and attempts to emit a SpanNode event. Must not throw regardless of whether the native
   * profiler is loaded.
   */
  @Test
  void onSpanFinished_profilerContext_doesNotThrow() {
    DatadogProfilingIntegration integration = new DatadogProfilingIntegration();

    // Mockito can create a mock that implements multiple interfaces
    AgentSpanContext ctx = mock(AgentSpanContext.class, org.mockito.Answers.RETURNS_DEFAULTS);
    ProfilerContext profilerCtx = mock(ProfilerContext.class);

    // We need a single object that satisfies both instanceof checks.
    // Use a hand-rolled stub instead.
    TestContext combinedCtx = new TestContext(42L, 7L, 1L, 3, 5, 0L, "");

    AgentSpan span = mock(AgentSpan.class);
    when(span.context()).thenReturn(combinedCtx);
    when(span.getStartTime()).thenReturn(1_700_000_000_000_000_000L);
    when(span.getDurationNano()).thenReturn(1_000_000L);

    assertDoesNotThrow(() -> integration.onSpanFinished(span));
  }

  /**
   * When execution-thread metadata is present, onSpanFinished should still be safe and attempt both
   * SpanNode and SpanExecutionThread event emission paths.
   */
  @Test
  void onSpanFinished_withExecutionThreadMetadata_doesNotThrow() {
    DatadogProfilingIntegration integration = new DatadogProfilingIntegration();
    TestContext combinedCtx = new TestContext(42L, 7L, 1L, 3, 5, 123L, "worker-123");

    AgentSpan span = mock(AgentSpan.class);
    when(span.context()).thenReturn(combinedCtx);
    when(span.getStartTime()).thenReturn(1_700_000_000_000_000_000L);
    when(span.getDurationNano()).thenReturn(1_000_000L);

    assertDoesNotThrow(() -> integration.onSpanFinished(span));
  }

  /** Null span must not throw (guard at top of onSpanFinished). */
  @Test
  void onSpanFinished_nullSpan_doesNotThrow() {
    DatadogProfilingIntegration integration = new DatadogProfilingIntegration();
    assertDoesNotThrow(() -> integration.onSpanFinished(null));
  }

  // ---------------------------------------------------------------------------
  // Stub: a single object that satisfies both AgentSpanContext and ProfilerContext
  // ---------------------------------------------------------------------------

  private static final class TestContext implements AgentSpanContext, ProfilerContext {

    private final long spanId;
    private final long parentSpanId;
    private final long rootSpanId;
    private final int encodedOp;
    private final int encodedResource;
    private final long executionThreadId;
    private final String executionThreadName;

    TestContext(
        long spanId,
        long parentSpanId,
        long rootSpanId,
        int encodedOp,
        int encodedResource,
        long executionThreadId,
        String executionThreadName) {
      this.spanId = spanId;
      this.parentSpanId = parentSpanId;
      this.rootSpanId = rootSpanId;
      this.encodedOp = encodedOp;
      this.encodedResource = encodedResource;
      this.executionThreadId = executionThreadId;
      this.executionThreadName = executionThreadName;
    }

    // ProfilerContext
    @Override
    public long getSpanId() {
      return spanId;
    }

    @Override
    public long getParentSpanId() {
      return parentSpanId;
    }

    @Override
    public long getRootSpanId() {
      return rootSpanId;
    }

    @Override
    public int getEncodedOperationName() {
      return encodedOp;
    }

    @Override
    public CharSequence getOperationName() {
      return "test-op";
    }

    @Override
    public int getEncodedResourceName() {
      return encodedResource;
    }

    @Override
    public CharSequence getResourceName() {
      return "test-resource";
    }

    @Override
    public long getExecutionThreadId() {
      return executionThreadId;
    }

    @Override
    public String getExecutionThreadName() {
      return executionThreadName;
    }

    // AgentSpanContext
    @Override
    public datadog.trace.api.DDTraceId getTraceId() {
      return datadog.trace.api.DDTraceId.ZERO;
    }

    @Override
    public datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector getTraceCollector() {
      return datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentTraceCollector
          .INSTANCE;
    }

    @Override
    public int getSamplingPriority() {
      return datadog.trace.api.sampling.PrioritySampling.UNSET;
    }

    @Override
    public Iterable<java.util.Map.Entry<String, String>> baggageItems() {
      return java.util.Collections.emptyList();
    }

    @Override
    public datadog.trace.api.datastreams.PathwayContext getPathwayContext() {
      return null;
    }

    @Override
    public boolean isRemote() {
      return false;
    }
  }
}
