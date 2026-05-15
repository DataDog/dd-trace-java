package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/** Helper for Java-level instrumentation that emits {@code datadog.TaskBlock} intervals. */
public final class TaskBlockHelper {
  static final long MIN_TASK_BLOCK_NANOS = 1_000_000L;

  private TaskBlockHelper() {}

  /** Captured state for a potential blocking interval. */
  public static final class State {
    final ProfilingContextIntegration profiling;
    final long startTicks;
    final long startNanos;
    final long spanId;
    final long rootSpanId;
    final long blocker;

    State(
        ProfilingContextIntegration profiling,
        long startTicks,
        long startNanos,
        long spanId,
        long rootSpanId,
        long blocker) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
      this.blocker = blocker;
    }
  }

  public static State capture(long blocker) {
    return capture(blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
  }

  static State capture(long blocker, ProfilingContextIntegration profiling, AgentSpan span) {
    if (profiling == null || span == null || !(span.context() instanceof ProfilerContext)) {
      return null;
    }
    ProfilerContext context = (ProfilerContext) span.context();
    return new State(
        profiling,
        profiling.getCurrentTicks(),
        System.nanoTime(),
        context.getSpanId(),
        context.getRootSpanId(),
        blocker);
  }

  public static void finish(State state) {
    if (state == null || System.nanoTime() - state.startNanos < MIN_TASK_BLOCK_NANOS) {
      return;
    }
    state.profiling.recordTaskBlock(
        state.startTicks, state.spanId, state.rootSpanId, state.blocker, 0L);
  }
}
