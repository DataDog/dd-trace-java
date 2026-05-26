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

  public static State captureForMonitor(Object monitor) {
    try {
      if (monitor == null) {
        return null;
      }
      return capture(System.identityHashCode(monitor));
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * Capture entry-point for {@code Thread.sleep} bracketing. The blocker key is {@code 0} because a
   * sleep has no associated monitor identity; consumers distinguishing sleep from other populations
   * should rely on the call-site context (or future helper-specific marker) rather than on {@code
   * State.blocker}. Returns {@code null} (so {@link #finish(State)} is a no-op) when no profiling
   * context is active — matches the fast-path of {@link #capture(long)} so sleep sites in untraced
   * code carry zero allocation cost.
   */
  public static State captureForSleep() {
    return captureSafely(0L);
  }

  /**
   * Capture entry-point for blocking I/O bracketing (currently used by the {@code nio-selector}
   * instrumentation). The blocker key is the file descriptor or fd-hash supplied by the caller (or
   * {@code 0} when not available, e.g. {@code Selector.select} which watches a set of fds). Returns
   * {@code null} when no profiling context is active.
   */
  public static State captureForIo(long fd) {
    return captureSafely(fd);
  }

  static State captureSafely(long blocker) {
    try {
      return capture(blocker);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static State captureSafely(long blocker, ProfilingContextIntegration profiling, AgentSpan span) {
    try {
      return capture(blocker, profiling, span);
    } catch (Throwable ignored) {
      return null;
    }
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
    try {
      state.profiling.recordTaskBlock(state.startTicks, state.blocker, 0L);
    } catch (Throwable ignored) {
      // Bytecode-injected sites must not propagate exceptions from instrumentation — a throw here
      // could leak a held monitor at a synchronized(obj){} site where the javac-emitted
      // try-region starts only after our injected finish() call.
    }
  }
}
