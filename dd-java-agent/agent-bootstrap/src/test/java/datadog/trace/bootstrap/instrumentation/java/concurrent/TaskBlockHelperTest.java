package datadog.trace.bootstrap.instrumentation.java.concurrent;

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

  private static final long SPAN_ID = 0xDEADBEEFL;
  private static final long ROOT_SPAN_ID = 0xCAFEBABEL;
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
  void capture_recordsActiveSpanAndEntryTiming() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    AgentSpan span = mock(AgentSpan.class);
    ProfilerSpanContext ctx = mock(ProfilerSpanContext.class);
    when(span.context()).thenReturn(ctx);
    when(ctx.getSpanId()).thenReturn(SPAN_ID);
    when(ctx.getRootSpanId()).thenReturn(ROOT_SPAN_ID);
    when(profiling.getCurrentTicks()).thenReturn(START_TICKS);

    long before = System.nanoTime();
    TaskBlockHelper.State state = TaskBlockHelper.capture(BLOCKER, profiling, span);
    long after = System.nanoTime();

    assertNotNull(state);
    assertEquals(profiling, state.profiling);
    assertEquals(START_TICKS, state.startTicks);
    assertTrue(state.startNanos >= before, "startNanos should be captured after `before`");
    assertTrue(state.startNanos <= after, "startNanos should be captured before `after`");
    assertEquals(SPAN_ID, state.spanId);
    assertEquals(ROOT_SPAN_ID, state.rootSpanId);
    assertEquals(BLOCKER, state.blocker);
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
            profiling,
            START_TICKS,
            System.nanoTime() + 60_000_000_000L,
            SPAN_ID,
            ROOT_SPAN_ID,
            BLOCKER);

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
            SPAN_ID,
            ROOT_SPAN_ID,
            BLOCKER);

    TaskBlockHelper.finish(state);

    verify(profiling).recordTaskBlock(START_TICKS, SPAN_ID, ROOT_SPAN_ID, BLOCKER, 0L);
  }
}
