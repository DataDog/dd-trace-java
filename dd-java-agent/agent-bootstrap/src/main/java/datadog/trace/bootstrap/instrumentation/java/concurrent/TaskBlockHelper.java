package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * Helper for Java-level instrumentation that emits {@code datadog.TaskBlock} intervals.
 *
 * <p>Span context is captured natively from the OTEP TLS sidecar at the {@code recordTaskBlock} JNI
 * boundary, matching the {@code recordQueueTime} convention. Java-side state only carries the
 * fields the native side cannot recompute (start tick, start nanos for the duration gate, blocker).
 */
public final class TaskBlockHelper {
  static final long MIN_TASK_BLOCK_NANOS = 1_000_000L;

  private TaskBlockHelper() {}

  /** Captured state for a potential blocking interval. */
  public static final class State {
    final ProfilingContextIntegration profiling;
    final long startTicks;
    final long startNanos;
    final long blocker;

    State(ProfilingContextIntegration profiling, long startTicks, long startNanos, long blocker) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
    }
  }

  public static State capture(long blocker) {
    return capture(blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
  }

  static State capture(long blocker, ProfilingContextIntegration profiling, AgentSpan span) {
    if (profiling == null) {
      return null;
    }
    // Keep the no-active-span early-out: avoids the JNI hop when the native side would only
    // count the call as a span-zero skip. The span itself is no longer read here.
    ProfilerContext context = ProfilerContexts.of(span);
    if (context == null) {
      return null;
    }
    return new State(profiling, profiling.getCurrentTicks(), System.nanoTime(), blocker);
  }

  public static void finish(State state) {
    if (state == null || System.nanoTime() - state.startNanos < MIN_TASK_BLOCK_NANOS) {
      return;
    }
    state.profiling.recordTaskBlock(state.startTicks, state.blocker, 0L);
  }
}
