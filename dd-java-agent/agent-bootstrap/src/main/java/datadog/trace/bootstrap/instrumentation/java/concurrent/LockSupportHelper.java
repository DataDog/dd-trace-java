// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.WeakMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/** Helper for profiling {@code LockSupport.park*} intervals from bootstrap instrumentation. */
public final class LockSupportHelper {
  private static final int MAX_UNPARKING_STATES = 50_000;

  /**
   * Best-effort association between a parked thread and the most recent {@code unpark} caller's
   * active span. Weak keys avoid retaining terminated threads and each target reuses a primitive
   * state holder to avoid boxing span identifiers.
   */
  static final WeakMap<Thread, UnparkState> UNPARKING_STATE = WeakMap.Supplier.newWeakMap();

  private static final AtomicLongFieldUpdater<UnparkState> UNBLOCKING_SPAN_ID =
      AtomicLongFieldUpdater.newUpdater(UnparkState.class, "unblockingSpanId");

  static final class UnparkState {
    volatile long unblockingSpanId;
  }

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
    WeakMap<Thread, UnparkState> unparkingState = UNPARKING_STATE;
    UnparkState state = unparkingState == null ? null : unparkingState.get(Thread.currentThread());
    long unblockingSpanId = state == null ? 0L : UNBLOCKING_SPAN_ID.getAndSet(state, 0L);
    parkExit(profiling, blockerHash, unblockingSpanId);
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
    recordUnpark(thread, UNPARKING_STATE);
  }

  static void recordUnpark(Thread thread, WeakMap<Thread, UnparkState> unparkingState) {
    if (thread == null || unparkingState == null) {
      return;
    }
    AgentSpan span = AgentTracer.activeSpan();
    AgentSpanContext context = span == null ? null : span.spanContext();
    if (context instanceof ProfilerContext) {
      UnparkState state = unparkingState.get(thread);
      if (state == null) {
        if (unparkingState.size() >= MAX_UNPARKING_STATES) {
          return;
        }
        unparkingState.putIfAbsent(thread, new UnparkState());
        state = unparkingState.get(thread);
      }
      if (state != null) {
        UNBLOCKING_SPAN_ID.set(state, ((ProfilerContext) context).getSpanId());
      }
    } else {
      UnparkState state = unparkingState.get(thread);
      if (state != null) {
        UNBLOCKING_SPAN_ID.set(state, 0L);
      }
    }
  }
}
