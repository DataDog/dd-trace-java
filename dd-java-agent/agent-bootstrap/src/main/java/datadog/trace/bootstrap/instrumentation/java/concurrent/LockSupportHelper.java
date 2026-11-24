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

  /**
   * Virtual threads currently inside {@code VirtualThread.parkOnCarrierThread}. Only pinned parks
   * insert, so the common path never allocates; an entry is consumed by the next virtual park exit
   * on the same thread. May be {@code null} during recursive class initialization, like above.
   */
  private static final WeakMap<Thread, Boolean> PINNED_PARKS = WeakMap.Supplier.newWeakMap();

  /** Set once any thread pinned, so the park path skips the map lookup until then. */
  private static volatile boolean pinnedSeen;

  /** Parks shorter than this are not worth a TaskBlock; matches the ddprof threshold. */
  static final long MIN_VIRTUAL_PARK_NANOS = 1_000_000L;

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

  /**
   * Returns a non-zero {@link System#nanoTime()} when the current thread is virtual and virtual
   * parks are recorded, else {@code 0}. The caller keeps it in an advice local and passes it to
   * {@link #virtualParkExit(long, long)}.
   */
  public static long virtualParkStart() {
    Thread thread = Thread.currentThread();
    if (!ThreadSupport.isVirtual(thread)) {
      return 0L;
    }
    ProfilingContextIntegration profiling = AgentTracer.get().getProfilingContext();
    if (profiling == null || !isVirtualParkEnabled(profiling)) {
      return 0L;
    }
    // A pin left by a non-LockSupport park (e.g. Thread.sleep) must not hide this park.
    consumePinned(thread);
    long now = System.nanoTime();
    return now == 0L ? 1L : now;
  }

  private static boolean isVirtualParkEnabled(ProfilingContextIntegration profiling) {
    try {
      return profiling.isVirtualParkEnabled();
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** Marks the current virtual thread as parking pinned to its carrier; such parks are skipped. */
  public static void markPinnedPark() {
    Thread thread = Thread.currentThread();
    if (PINNED_PARKS != null && ThreadSupport.isVirtual(thread)) {
      pinnedSeen = true;
      PINNED_PARKS.put(thread, Boolean.TRUE);
    }
  }

  private static boolean consumePinned(Thread thread) {
    return pinnedSeen && PINNED_PARKS != null && PINNED_PARKS.remove(thread) != null;
  }

  /**
   * Reports a virtual-thread park that ended on the current thread. Does nothing for {@code
   * startNanos == 0}, pinned parks (already visible in the carrier's wall-clock samples), and parks
   * shorter than the profiler threshold. The unblocking span is not tracked for virtual threads and
   * is reported as {@code 0}.
   */
  public static void virtualParkExit(long startNanos, long blockerHash) {
    if (startNanos == 0L) {
      return;
    }
    long elapsed = System.nanoTime() - startNanos;
    if (consumePinned(Thread.currentThread())) {
      return;
    }
    reportVirtualPark(elapsed, blockerHash);
  }

  static void reportVirtualPark(long elapsedNanos, long blockerHash) {
    if (elapsedNanos < MIN_VIRTUAL_PARK_NANOS) {
      return;
    }
    ProfilingContextIntegration profiling = AgentTracer.get().getProfilingContext();
    if (profiling == null) {
      return;
    }
    try {
      profiling.virtualParkExit(elapsedNanos, blockerHash, 0L);
    } catch (Throwable ignored) {
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
