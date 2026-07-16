// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Helper for profiling {@code LockSupport.park*} intervals from bootstrap instrumentation. */
public final class LockSupportHelper {
  /**
   * Best-effort association between a parked thread and the most recent {@code unpark} caller's
   * active span. Weak keys avoid retaining terminated threads; the synchronized wrapper protects
   * calls made concurrently by parked and unparking threads.
   */
  static final Map<Thread, Long> UNPARKING_SPAN = Collections.synchronizedMap(new WeakHashMap<>());

  private LockSupportHelper() {}

  /** State required to balance a park entry accepted by a profiling integration. */
  public static final class ParkState {
    private final ProfilingContextIntegration profiling;
    private final long blockerHash;

    ParkState(ProfilingContextIntegration profiling, long blockerHash) {
      this.profiling = profiling;
      this.blockerHash = blockerHash;
    }
  }

  /** Captures a park entry through the currently installed profiling integration. */
  public static ParkState captureState(Object blocker) {
    return captureState(blocker, AgentTracer.get().getProfilingContext());
  }

  static ParkState captureState(Object blocker, ProfilingContextIntegration profiling) {
    if (profiling == null) {
      return null;
    }
    try {
      if (!profiling.parkEnter()) {
        return null;
      }
      return new ParkState(
          profiling,
          blocker == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(blocker)));
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Drains unpark attribution and balances an accepted park entry. */
  public static void finish(ParkState state) {
    Long unblockingSpanId = UNPARKING_SPAN.remove(Thread.currentThread());
    finish(state, unblockingSpanId == null ? 0L : unblockingSpanId);
  }

  static void finish(ParkState state, long unblockingSpanId) {
    if (state == null) {
      return;
    }
    try {
      state.profiling.parkExit(state.blockerHash, unblockingSpanId);
    } catch (Throwable ignored) {
    }
  }

  /**
   * Records the latest unpark caller for {@code thread}. An untraced call explicitly clears an
   * older traced caller so the association follows last-writer semantics.
   */
  public static void recordUnpark(Thread thread) {
    if (thread == null) {
      return;
    }
    AgentSpan span = AgentTracer.activeSpan();
    AgentSpanContext context = span == null ? null : span.spanContext();
    if (context instanceof ProfilerContext) {
      UNPARKING_SPAN.put(thread, ((ProfilerContext) context).getSpanId());
    } else {
      UNPARKING_SPAN.remove(thread);
    }
  }
}
