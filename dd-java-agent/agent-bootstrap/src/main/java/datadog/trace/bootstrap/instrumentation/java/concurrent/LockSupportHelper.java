package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.ConcurrentHashMap;

/** Helper for profiling {@code LockSupport.park*} intervals from bootstrap classes. */
public final class LockSupportHelper {
  /** Maps target thread to the span ID of the thread that called {@code unpark()} on it. */
  public static final ConcurrentHashMap<Thread, Long> UNPARKING_SPAN = new ConcurrentHashMap<>();

  private LockSupportHelper() {}

  /** Captured state for a {@code LockSupport.park*} interval. */
  public static final class ParkState {
    public final ProfilingContextIntegration profiling;
    public final long blockerHash;
    public final long spanId;
    public final long rootSpanId;

    public ParkState(
        ProfilingContextIntegration profiling, long blockerHash, long spanId, long rootSpanId) {
      this.profiling = profiling;
      this.blockerHash = blockerHash;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }
  }

  public static ParkState captureState(Object blocker) {
    return captureState(blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
  }

  public static ParkState captureState(
      Object blocker, ProfilingContextIntegration profiling, AgentSpan span) {
    if (profiling == null) {
      return null;
    }
    // Always call parkEnter for signal suppression, even without an active span.
    // spanId/rootSpanId = 0 when no active span, and native TaskBlock eligibility filters out
    // zero-span intervals at exit.
    long spanId = 0L;
    long rootSpanId = 0L;
    if (span != null && span.context() instanceof ProfilerContext) {
      ProfilerContext ctx = (ProfilerContext) span.context();
      spanId = ctx.getSpanId();
      rootSpanId = ctx.getRootSpanId();
    }
    profiling.parkEnter(spanId, rootSpanId);
    long blockerHash = blocker != null ? System.identityHashCode(blocker) : 0L;
    return new ParkState(profiling, blockerHash, spanId, rootSpanId);
  }

  public static void finish(ParkState state) {
    // Always drain the map entry before any early return. If we returned first, a stale
    // unblocking-span ID placed by a prior unpark() would persist and be incorrectly
    // attributed to the next TaskBlock event emitted on this thread.
    Long unblockingSpanId = UNPARKING_SPAN.remove(Thread.currentThread());
    finish(state, unblockingSpanId != null ? unblockingSpanId : 0L);
  }

  public static void finish(ParkState state, long unblockingSpanId) {
    if (state == null) {
      return;
    }
    // parkExit() clears native parked state and records an eligible TaskBlock using the entry
    // tick saved by parkEnter().
    state.profiling.parkExit(state.blockerHash, unblockingSpanId);
  }

  public static void recordUnpark(Thread thread) {
    if (thread == null) {
      return;
    }
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null || !(span.context() instanceof ProfilerContext)) {
      return;
    }
    ProfilerContext ctx = (ProfilerContext) span.context();
    UNPARKING_SPAN.put(thread, ctx.getSpanId());
  }
}
