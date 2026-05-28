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
 * <p>For virtual threads (JDK 21+), the native OTEP TLS sidecar is carrier-OS-thread-scoped and
 * cannot be trusted between capture and emit. Virtual thread call sites capture span/root ids at
 * block entry and pass them explicitly via {@link
 * ProfilingContextIntegration#recordTaskBlockWithContext}, bypassing the TLS sidecar. This path is
 * span/root-only; custom profiling context attributes are not propagated.
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

    /** Span ID captured at block entry; only meaningful when {@code isVirtual == true}. */
    final long spanId;

    /** Root span ID captured at block entry; only meaningful when {@code isVirtual == true}. */
    final long rootSpanId;

    /** Constructor for platform threads. */
    State(ProfilingContextIntegration profiling, long startTicks, long startNanos, long blocker) {
      this.profiling = profiling;
      this.startTicks = startTicks;
      this.startNanos = startNanos;
      this.blocker = blocker;
      this.isVirtual = false;
      this.spanId = 0L;
      this.rootSpanId = 0L;
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
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }
  }

  public static State capture(long blocker) {
    return capture(blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
  }

  /**
   * Capture entry-point for monitor-based blocking: {@code Object.wait()} (via the {@code
   * object-wait} module) and {@code MONITORENTER} contention (via the {@code
   * synchronized-contention} module). The blocker key is {@link System#identityHashCode} of the
   * monitor object, matching the native JVMTI path convention. Returns {@code null} if {@code
   * monitor} is {@code null} or if no profiling context is active.
   */
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
   * context is active - matches the fast-path of {@link #capture(long)} so sleep sites in untraced
   * code carry zero allocation cost.
   */
  public static State captureForSleep() {
    return captureSafely(0L);
  }

  /**
   * Capture entry-point for blocking I/O bracketing. Used by the {@code nio-selector} module (all
   * {@code Selector.select*} variants, blocker {@code 0} - no single fd identity). Returns {@code
   * null} when no profiling context is active.
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
    // count the call as a span-zero skip. The span itself is no longer read here for platform
    // threads (context is read from OTEP TLS at JNI boundary).
    ProfilerContext context = ProfilerContexts.of(span);
    if (context == null) {
      return null;
    }
    long startTicks = profiling.getCurrentTicks();
    long startNanos = System.nanoTime();
    if (VirtualThreads.isCurrent()) {
      // Virtual thread: capture span/root ids now so they can be passed explicitly to the native
      // deferred-capture path, which bypasses the carrier-scoped OTEP TLS sidecar.
      return new State(
          profiling, startTicks, startNanos, blocker, context.getSpanId(), context.getRootSpanId());
    }
    return new State(profiling, startTicks, startNanos, blocker);
  }

  public static void finish(State state) {
    // Java-side pre-check: skip the JNI hop when obviously below the 1 ms minimum.
    // The native side (recordTaskBlockLiveIfEligible) applies a second gate using TSC
    // ticks - the authoritative check. Both thresholds are 1 ms; for values well above
    // that they agree. This pre-check is an optimisation only and is not present on the
    // LockSupport.park* path, which has a single native gate via parkExit().
    if (state == null || System.nanoTime() - state.startNanos < MIN_TASK_BLOCK_NANOS) {
      return;
    }
    try {
      if (state.isVirtual) {
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
    }
  }
}
