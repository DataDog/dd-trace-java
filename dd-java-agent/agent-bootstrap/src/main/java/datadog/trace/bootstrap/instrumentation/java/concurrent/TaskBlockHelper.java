package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * Helper for Java-level instrumentation that emits {@code datadog.TaskBlock} intervals.
 *
 * <p>For platform threads, span context is captured natively from the OTEP TLS sidecar at the
 * {@code recordTaskBlock} JNI boundary, matching the {@code recordQueueTime} convention. Java-side
 * state only carries the fields the native side cannot recompute (start tick, start nanos for the
 * duration gate, blocker).
 *
 * <p>For virtual threads (JDK 21+) and deferred platform-thread recording, the native OTEP TLS
 * sidecar cannot be trusted at emit time. Those call sites capture span/root ids at block entry and
 * pass them explicitly via {@link ProfilingContextIntegration#recordTaskBlockWithContext} or {@link
 * ProfilingContextIntegration#enqueueTaskBlock}, bypassing the TLS sidecar. The deferred queue is
 * only used for platform threads. These paths are span/root-only; custom profiling context
 * attributes are not propagated.
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

    /** {@code true} when the blocking thread is a virtual thread. */
    final boolean isVirtual;

    /**
     * {@code true} for the {@code Thread.sleep} path: {@link #finish} enqueues the event to a
     * bounded background queue instead of making a synchronous JNI call. This removes the JFR write
     * from the critical request path while keeping data collection intact.
     */
    final boolean deferred;

    /** Opaque native blocked-run token; non-zero only for platform Thread.sleep. */
    final long blockToken;

    /** Span ID captured at block entry; meaningful for virtual or deferred paths. */
    final long spanId;

    /** Root span ID captured at block entry; meaningful for virtual or deferred paths. */
    final long rootSpanId;

    /** Constructor for platform threads (non-deferred synchronous recording). */
    State(ProfilingContextIntegration profiling, long startTicks, long startNanos, long blocker) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = false;
      this.deferred = false;
      this.blockToken = 0L;
      this.spanId = 0L;
      this.rootSpanId = 0L;
    }

    /** Constructor for platform threads with optional deferred (async) recording. */
    State(
        ProfilingContextIntegration profiling,
        long startTicks,
        long startNanos,
        long blocker,
        boolean deferred,
        long spanId,
        long rootSpanId) {
      this(profiling, startTicks, startNanos, blocker, deferred, spanId, rootSpanId, 0L);
    }

    /** Constructor for platform threads with optional deferred recording and native block state. */
    State(
        ProfilingContextIntegration profiling,
        long startTicks,
        long startNanos,
        long blocker,
        boolean deferred,
        long spanId,
        long rootSpanId,
        long blockToken) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = false;
      this.deferred = deferred;
      this.blockToken = blockToken;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }

    /** Constructor for virtual threads: span/root ids are captured at entry time. */
    State(
        ProfilingContextIntegration profiling,
        long startTicks,
        long startNanos,
        long blocker,
        long spanId,
        long rootSpanId) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = true;
      this.deferred = false;
      this.blockToken = 0L;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }
  }

  public static State capture(long blocker) {
    return capture(blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
  }

  /**
   * Capture entry-point for {@code Thread.sleep} bracketing. Platform threads return a deferred
   * {@link State}: when {@link #finish} is called, the TaskBlock event is enqueued to a bounded
   * background queue instead of making a synchronous JNI call. They also arm native blocked-run
   * state so the wall-clock timer can skip later signals after the first MethodSample in the sleep
   * run. Virtual threads return a virtual {@link State} and bypass both the platform-thread queue
   * and native carrier-thread blocked state.
   *
   * <p>The blocker key is {@code 0} (sleep has no monitor identity). Returns {@code null} when no
   * profiling context is active — sleep sites in untraced code carry zero allocation cost.
   */
  public static State captureForSleep() {
    return captureSafely(0L, true);
  }

  static State captureSafely(long blocker) {
    return captureSafely(blocker, false);
  }

  static State captureSafely(long blocker, boolean deferred) {
    try {
      return capture(
          blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan(), deferred);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static State captureSafely(long blocker, ProfilingContextIntegration profiling, AgentSpan span) {
    try {
      return capture(blocker, profiling, span, false);
    } catch (Throwable ignored) {
      return null;
    }
  }

  static State capture(long blocker, ProfilingContextIntegration profiling, AgentSpan span) {
    return capture(blocker, profiling, span, false);
  }

  static State capture(
      long blocker, ProfilingContextIntegration profiling, AgentSpan span, boolean deferred) {
    if (profiling == null) {
      return null;
    }
    // Keep the no-active-span early-out: avoids the JNI hop when the native side would only
    // count the call as a span-zero skip. Synchronous platform threads use OTEP TLS at the JNI
    // boundary; virtual and deferred paths carry entry-time ids explicitly.
    ProfilerContext context = ProfilerContexts.of(span);
    if (context == null) {
      return null;
    }
    long startTicks = profiling.getCurrentTicks();
    long startNanos = System.nanoTime();
    if (VirtualThreads.isCurrent()) {
      // Virtual thread path: capture span/root ids now so they can be passed explicitly to the
      // native context path, which bypasses the carrier-scoped OTEP TLS sidecar.
      return new State(
          profiling, startTicks, startNanos, blocker, context.getSpanId(), context.getRootSpanId());
    }
    if (deferred) {
      // Deferred platform-thread path: capture span/root ids at entry because the background drain
      // thread cannot use the request thread's OTEP TLS sidecar. Also arm the native sleep state so
      // wall-clock can suppress later signals in this span-scoped sleep run.
      long blockToken = profiling.blockEnter(ProfilingContextIntegration.BLOCKING_STATE_SLEEPING);
      return new State(
          profiling,
          startTicks,
          startNanos,
          blocker,
          true,
          context.getSpanId(),
          context.getRootSpanId(),
          blockToken);
    }
    return new State(profiling, startTicks, startNanos, blocker);
  }

  public static void finish(State state) {
    if (state == null) {
      return;
    }
    try {
      // Java-side pre-check: skip the JNI hop when obviously below the 1 ms minimum.
      // The native side (recordTaskBlockLiveIfEligible) applies a second gate using TSC
      // ticks - the authoritative check. Both thresholds are 1 ms; for values well above
      // that they agree. This pre-check is an optimisation only and is not present on the
      // LockSupport.park* path, which has a single native gate via parkExit().
      if (System.nanoTime() - state.startNanos < MIN_TASK_BLOCK_NANOS) {
        return;
      }
      if (state.deferred) {
        // Deferred async path (Thread.sleep on platform threads): enqueue to a bounded queue so the
        // JFR write happens off the critical request path. Use entry-time span/root ids because the
        // active span at finish may already have changed.
        if (state.spanId == 0L) {
          return;
        }
        long durationNanos = System.nanoTime() - state.startNanos;
        state.profiling.enqueueTaskBlock(
            state.startTicks, durationNanos, state.blocker, state.spanId, state.rootSpanId);
      } else if (state.isVirtual) {
        // Virtual thread: pass the entry-time span/root ids explicitly to bypass OTEP TLS.
        state.profiling.recordTaskBlockWithContext(
            state.startTicks, state.blocker, 0L, state.spanId, state.rootSpanId);
      } else {
        state.profiling.recordTaskBlock(state.startTicks, state.blocker, 0L);
      }
    } catch (Throwable ignored) {
      // Bytecode-injected sites must not propagate exceptions from instrumentation - a throw here
      // could leak a held monitor at a synchronized(obj){} site where the javac-emitted
      // try-region starts only after our injected finish() call.
    } finally {
      if (state.blockToken != 0L) {
        try {
          state.profiling.blockExit(state.blockToken);
        } catch (Throwable ignored) {
        }
      }
    }
  }
}
