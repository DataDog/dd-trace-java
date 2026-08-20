// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.environment.ThreadSupport;
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

  private static final AtomicLongFieldUpdater<UnparkState> UNBLOCKING_SPAN_ID =
      AtomicLongFieldUpdater.newUpdater(UnparkState.class, "unblockingSpanId");

  /**
   * Best-effort association between a parked thread and the most recent {@code unpark} caller's
   * active span. Weak keys avoid retaining terminated threads and each target reuses a primitive
   * state holder to avoid boxing span identifiers.
   *
   * <p>Deliberately the last field to be initialized: building the map itself parks and unparks
   * threads, so the instrumented {@code LockSupport} methods can re-enter this class on the very
   * thread running {@code <clinit>}. A {@code null} read is therefore a legal observation meaning
   * "initialization still in progress", and every reader must tolerate it.
   */
  static final WeakMap<Thread, UnparkState> UNPARKING_STATE = WeakMap.Supplier.newWeakMap();

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
    parkExit(profiling, blockerHash, UNPARKING_STATE);
  }

  static void parkExit(
      ProfilingContextIntegration profiling,
      long blockerHash,
      WeakMap<Thread, UnparkState> unparkingState) {
    if (profiling == null) {
      return;
    }
    UnparkState state = getUnparkState(unparkingState, Thread.currentThread());
    long unblockingSpanId = state == null ? 0L : UNBLOCKING_SPAN_ID.getAndSet(state, 0L);
    parkExit(profiling, blockerHash, unblockingSpanId);
  }

  /**
   * Guards against {@code unparkingState} still being {@code null} during recursive class
   * initialization. Shared by {@link #parkExit} and {@link #recordUnpark} so both methods use the
   * same null-safety idiom.
   */
  private static UnparkState getUnparkState(
      WeakMap<Thread, UnparkState> unparkingState, Thread thread) {
    return unparkingState == null ? null : unparkingState.get(thread);
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
    // Virtual-thread park intervals are rejected by the profiler, so their state would never be
    // drained by parkExit and would only be reclaimed by unpredictable GC of the weak key. Virtual
    // threads are created and destroyed often, so skip them outright.
    if (thread == null || ThreadSupport.isVirtual(thread)) {
      return;
    }
    // unpark is extremely hot; skip the active span lookup (which installs a scope stack thread
    // local on every unparking thread) unless the profiler can actually consume the attribution.
    ProfilingContextIntegration profiling = AgentTracer.get().getProfilingContext();
    if (profiling == null || !profiling.isUnparkAttributionEnabled()) {
      return;
    }
    recordUnpark(thread, UNPARKING_STATE);
  }

  static void recordUnpark(Thread thread, WeakMap<Thread, UnparkState> unparkingState) {
    if (thread == null || unparkingState == null) {
      return;
    }
    AgentSpan span = AgentTracer.activeSpan();
    AgentSpanContext context = span == null ? null : span.spanContext();
    if (context instanceof ProfilerContext) {
      UnparkState state = getUnparkState(unparkingState, thread);
      if (state == null) {
        if (unparkingState.size() >= MAX_UNPARKING_STATES) {
          return;
        }
        unparkingState.putIfAbsent(thread, new UnparkState());
        state = getUnparkState(unparkingState, thread);
      }
      if (state != null) {
        UNBLOCKING_SPAN_ID.set(state, ((ProfilerContext) context).getSpanId());
      }
    } else {
      UnparkState state = getUnparkState(unparkingState, thread);
      if (state != null) {
        UNBLOCKING_SPAN_ID.set(state, 0L);
      }
    }
  }
}
