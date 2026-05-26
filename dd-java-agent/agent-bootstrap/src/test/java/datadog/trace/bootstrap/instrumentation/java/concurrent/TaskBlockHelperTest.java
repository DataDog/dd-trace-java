package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import org.junit.jupiter.api.Test;

class TaskBlockHelperTest {

  private static final long START_TICKS = 42_000_000L;
  private static final long BLOCKER = 1234L;

  private interface ProfilerSpanContext extends AgentSpanContext, ProfilerContext {}

  @Test
  void capture_returnsNull_withoutProfilingContext() {
    AgentSpan span = mock(AgentSpan.class);

    assertNull(TaskBlockHelper.capture(BLOCKER, null, span));
  }

  @Test
  void capture_returnsNull_withoutActiveSpan() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    assertNull(TaskBlockHelper.capture(BLOCKER, profiling, null));
  }

  @Test
  void capture_returnsNull_whenSpanContextIsNotProfilerContext() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    AgentSpan nonProfilerSpan = mock(AgentSpan.class);
    AgentSpanContext nonProfilerCtx = mock(AgentSpanContext.class);
    when(nonProfilerSpan.context()).thenReturn(nonProfilerCtx);

    assertNull(TaskBlockHelper.capture(BLOCKER, profiling, nonProfilerSpan));
  }

  @Test
  void capture_recordsEntryTimingWithoutSpanIds() {
    // Span identity is captured natively at the recordTaskBlock JNI boundary; the Java-side
    // State only retains the fields the native side cannot recompute (start tick, start nanos,
    // blocker). The presence of an active span on the thread is still gated by capture() so we
    // skip the JNI hop for span-less intervals.
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    AgentSpan span = mock(AgentSpan.class);
    ProfilerSpanContext ctx = mock(ProfilerSpanContext.class);
    when(span.context()).thenReturn(ctx);
    when(profiling.getCurrentTicks()).thenReturn(START_TICKS);

    long before = System.nanoTime();
    TaskBlockHelper.State state = TaskBlockHelper.capture(BLOCKER, profiling, span);
    long after = System.nanoTime();

    assertNotNull(state);
    assertEquals(profiling, state.profiling);
    assertEquals(START_TICKS, state.startTicks);
    assertTrue(state.startNanos >= before, "startNanos should be captured after `before`");
    assertTrue(state.startNanos <= after, "startNanos should be captured before `after`");
    assertEquals(BLOCKER, state.blocker);
  }

  @Test
  void captureSafely_returnsNullWhenEntryCaptureThrows() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    AgentSpan span = mock(AgentSpan.class);
    ProfilerSpanContext ctx = mock(ProfilerSpanContext.class);
    when(span.context()).thenReturn(ctx);
    when(profiling.getCurrentTicks()).thenThrow(new RuntimeException("boom"));

    assertNull(TaskBlockHelper.captureSafely(BLOCKER, profiling, span));
  }

  @Test
  void finish_ignoresNullState() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    TaskBlockHelper.finish(null);

    verifyNoInteractions(profiling);
  }

  @Test
  void finish_ignoresTooShortIntervals() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    // startNanos far in the future so (now - startNanos) is negative and below threshold
    TaskBlockHelper.State state =
        new TaskBlockHelper.State(
            profiling, START_TICKS, System.nanoTime() + 60_000_000_000L, BLOCKER);

    TaskBlockHelper.finish(state);

    verifyNoInteractions(profiling);
  }

  @Test
  void finish_emitsTaskBlockForEligibleInterval() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    TaskBlockHelper.State state =
        new TaskBlockHelper.State(
            profiling,
            START_TICKS,
            System.nanoTime() - 2 * TaskBlockHelper.MIN_TASK_BLOCK_NANOS,
            BLOCKER);

    TaskBlockHelper.finish(state);

    // Span ids are no longer passed across JNI; the native side reads them from OTEP TLS.
    verify(profiling).recordTaskBlock(START_TICKS, BLOCKER, 0L);
  }

  @Test
  void finish_swallowsProfilerFailures() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    TaskBlockHelper.State state =
        new TaskBlockHelper.State(
            profiling,
            START_TICKS,
            System.nanoTime() - 2 * TaskBlockHelper.MIN_TASK_BLOCK_NANOS,
            BLOCKER);
    org.mockito.Mockito.doThrow(new RuntimeException("boom"))
        .when(profiling)
        .recordTaskBlock(START_TICKS, BLOCKER, 0L);

    assertDoesNotThrow(() -> TaskBlockHelper.finish(state));
  }
}
