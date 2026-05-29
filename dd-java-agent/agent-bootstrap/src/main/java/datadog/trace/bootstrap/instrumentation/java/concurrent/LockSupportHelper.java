package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Helper for profiling {@code LockSupport.park*} intervals from bootstrap classes.
 *
 * <h3>Known limitation: unpark attribution is best effort</h3>
 *
 * <p>{@link #UNPARKING_SPAN} pairs an {@code unpark(t)} caller's span id with the next {@code
 * park*} return on thread {@code t}. {@code LockSupport.park*} is documented to be allowed to
 * return spuriously, in which case the parked thread re-parks without ever consuming the map entry.
 * A subsequent, unrelated {@code park*} call on the same thread will then drain the stale entry and
 * incorrectly attribute the unblocking span to a {@code TaskBlock} it did not cause. Repeated
 * {@code unpark(t)} calls before one {@code park*} return are also lossy: LockSupport's permit is
 * one-bit and this map stores only the latest caller span, so an earlier causal unpark can be
 * overwritten by a later non-causal one.
 *
 * <p>We accept this residual race because the correct fix (per-park sequence numbers carried
 * through {@code ProfilerContext} and matched on entry) is disproportionate to the rarity of these
 * edge cases on the JDKs we target, and because the worst-case impact is a single mis-attributed
 * {@code TaskBlock} event per occurrence.
 *
 * <h3>Virtual thread handling</h3>
 *
 * <p>Virtual threads (JDK 21+) are multiplexed on OS carrier threads. The native {@code
 * parkEnter0}/{@code parkExit0} JNI methods use carrier-OS-thread-local storage, so multiple
 * virtual threads sharing a carrier overwrite each other's park state. For virtual threads we skip
 * the native park entry/exit pair and instead:
 *
 * <ol>
 *   <li>Capture the active span/root ids on the Java side at block entry.
 *   <li>On unpark, call {@link ProfilingContextIntegration#recordTaskBlockWithContext} which passes
 *       those ids explicitly to the native deferred-capture path, bypassing OTEP TLS. This path is
 *       span/root-only; custom profiling context attributes are not propagated.
 * </ol>
 */
public final class LockSupportHelper {
  /**
   * Maps a target thread to the span ID of the thread that called {@code unpark()} on it. Keyed by
   * the {@link Thread} object (identity equality) so that entries for terminated threads are
   * automatically reclaimed by the GC: the JVM holds a strong reference to every live thread
   * internally, so a weak key becomes unreachable only after the thread has terminated and been
   * collected. {@link WeakHashMap} polls its internal {@link java.lang.ref.ReferenceQueue} on every
   * structural operation, so stale entries are cleaned up eagerly without a dedicated thread.
   *
   * <p>Thread-safety: wrapped with {@link Collections#synchronizedMap}. All access must go through
   * the helper methods; direct map manipulation is only permitted in tests.
   */
  public static final Map<Thread, Long> UNPARKING_SPAN =
      Collections.synchronizedMap(new WeakHashMap<>());

  private LockSupportHelper() {}

  /** Captured state for a {@code LockSupport.park*} interval. */
  public static final class ParkState {
    public final ProfilingContextIntegration profiling;
    public final long blockerHash;

    /** {@code true} when the parking thread is a virtual thread. */
    public final boolean isVirtual;

    /** TSC tick captured at block entry; only meaningful when {@code isVirtual == true}. */
    public final long startTicks;

    /** Span ID captured at block entry; only meaningful when {@code isVirtual == true}. */
    public final long spanId;

    /** Root span ID captured at block entry; only meaningful when {@code isVirtual == true}. */
    public final long rootSpanId;

    /** Constructor for platform threads: no span context captured here (OTEP TLS used instead). */
    public ParkState(ProfilingContextIntegration profiling, long blockerHash) {
      this.profiling = profiling;
      this.blockerHash = blockerHash;
      this.isVirtual = false;
      this.startTicks = 0L;
      this.spanId = 0L;
      this.rootSpanId = 0L;
    }

    /** Constructor for virtual threads: span/root ids are captured at entry time. */
    public ParkState(
        ProfilingContextIntegration profiling,
        long blockerHash,
        long startTicks,
        long spanId,
        long rootSpanId) {
      this.profiling = profiling;
      this.blockerHash = blockerHash;
      this.isVirtual = true;
      this.startTicks = startTicks;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }
  }

  public static ParkState captureState(Object blocker) {
    return captureState(blocker, AgentTracer.get().getProfilingContext());
  }

  public static ParkState captureState(Object blocker, ProfilingContextIntegration profiling) {
    if (profiling == null) {
      return null;
    }
    long blockerHash = blocker != null ? System.identityHashCode(blocker) : 0L;
    if (VirtualThreads.isCurrent()) {
      // Virtual thread: skip native parkEnter0 (carrier-scoped TLS is unsafe).
      // Capture span/root ids now so we can pass them explicitly on unpark.
      ProfilerContext ctx = ProfilerContexts.of(AgentTracer.activeSpan());
      if (ctx == null) {
        // No active span - nothing to record.
        UNPARKING_SPAN.remove(Thread.currentThread());
        return null;
      }
      long startTicks;
      try {
        startTicks = profiling.getCurrentTicks();
      } catch (Throwable ignored) {
        UNPARKING_SPAN.remove(Thread.currentThread());
        return null;
      }
      return new ParkState(
          profiling, blockerHash, startTicks, ctx.getSpanId(), ctx.getRootSpanId());
    }

    // Platform thread: skip parkEnter() JNI when no span is active — native would discard
    // the interval at parkExit() anyway (zero-span eligibility check). Mirrors the guard
    // already present on the virtual thread path above.
    ProfilerContext ctx = ProfilerContexts.of(AgentTracer.activeSpan());
    if (ctx == null) {
      UNPARKING_SPAN.remove(Thread.currentThread());
      return null;
    }
    try {
      profiling.parkEnter();
    } catch (Throwable ignored) {
      // parkEnter failed (e.g. profiler not yet initialised, JNI error); do not track this park.
      // Drain any stale unblocking-span entry so it is not mis-attributed to the next park.
      UNPARKING_SPAN.remove(Thread.currentThread());
      return null;
    }
    return new ParkState(profiling, blockerHash);
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
    if (state.isVirtual) {
      // Virtual thread: emit via explicit-context path if a span was active at entry.
      if (state.spanId != 0L) {
        try {
          state.profiling.recordTaskBlockWithContext(
              state.startTicks,
              state.blockerHash,
              unblockingSpanId,
              state.spanId,
              state.rootSpanId);
        } catch (Throwable ignored) {
        }
      }
    } else {
      // Platform thread: parkExit() clears native parked state and records an eligible TaskBlock
      // using the entry tick saved by parkEnter().
      state.profiling.parkExit(state.blockerHash, unblockingSpanId);
    }
  }

  public static void recordUnpark(Thread thread) {
    if (thread == null) {
      return;
    }
    ProfilerContext ctx = ProfilerContexts.of(AgentTracer.activeSpan());
    if (ctx == null) {
      return;
    }
    UNPARKING_SPAN.put(thread, ctx.getSpanId());
  }
}
