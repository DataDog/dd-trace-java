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

  /** Returns the profiling integration when it accepts ownership of the park interval. */
  public static ProfilingContextIntegration parkEnter() {
    return parkEnter(AgentTracer.get().getProfilingContext());
  }

  static ProfilingContextIntegration parkEnter(ProfilingContextIntegration profiling) {
    if (profiling == null) {
      return null;
    }
    try {
      return profiling.parkEnter() ? profiling : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** Drains unpark attribution and balances an accepted park entry. */
  public static void parkExit(ProfilingContextIntegration profiling, long blockerHash) {
    if (profiling == null) {
      return;
    }
    Long unblockingSpanId = UNPARKING_SPAN.remove(Thread.currentThread());
    parkExit(profiling, blockerHash, unblockingSpanId == null ? 0L : unblockingSpanId);
  }

  static void parkExit(
      ProfilingContextIntegration profiling, long blockerHash, long unblockingSpanId) {
    if (profiling == null) {
      return;
    }
    try {
      profiling.parkExit(blockerHash, unblockingSpanId);
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
