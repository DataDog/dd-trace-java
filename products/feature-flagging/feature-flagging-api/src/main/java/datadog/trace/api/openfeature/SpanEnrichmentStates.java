package datadog.trace.api.openfeature;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, instance-owned store of per-trace {@link SpanEnrichmentAccumulator} state, keyed by the
 * local-root span's full trace id (the lower-case zero-padded hex string).
 *
 * <p>The full hex key matters for 128-bit trace ids: keying by {@code DDTraceId.toLong()} (the
 * low-order 64 bits only) would merge two distinct 128-bit traces that share their low bits into a
 * single accumulator, cross-contaminating enrichment between unrelated traces. The full hex string
 * is unique across all 128 bits and is cached on the id, so it is cheap to obtain.
 *
 * <p>Each {@link SpanEnrichmentHook}/{@link SpanEnrichmentInterceptor} pair shares one store
 * instance, so:
 *
 * <ul>
 *   <li><b>Unbounded leak:</b> the store is hard-capped at {@link #MAX_TRACES}. A trace that never
 *       reaches the interceptor (dropped, Noop tracer, never-finishing root) can no longer leak
 *       unboundedly — once the cap is reached, the <em>oldest</em> in-flight entry is evicted (FIFO
 *       by insertion order) so the map size is strictly bounded regardless of trace completion.
 *   <li><b>Isolation:</b> because the store is instance-owned rather than a shared static, one
 *       provider's cleanup clears only its own state and can never wipe another (still-active)
 *       provider's in-flight entries.
 * </ul>
 *
 * <p>Bounded eviction is intentionally lossy under pathological pressure: dropping the oldest
 * accumulator degrades enrichment for that one (likely already-abandoned) trace rather than
 * exhausting the heap. Enrichment correctness is best-effort by contract; heap safety is not.
 *
 * <p>Thread-safety: all access is guarded by the intrinsic lock on this instance. The hook writes
 * (eval thread) and the interceptor reads+removes (trace-write thread) concurrently, so every
 * mutator and accessor synchronizes. Contention is low — operations are O(1) map touches.
 */
final class SpanEnrichmentStates {

  private static final Logger log = LoggerFactory.getLogger(SpanEnrichmentStates.class);

  /**
   * Hard cap on the number of concurrently-tracked traces. Sized well above any realistic count of
   * simultaneously in-flight traces with active flag evaluations on a single JVM, so the live path
   * never evicts; the cap exists purely to bound a leak of never-completing traces.
   */
  static final int MAX_TRACES = 4096;

  // Insertion-ordered so the eldest entry is the natural eviction victim (FIFO). accessOrder=false
  // (the default) — we evict by age of creation, not by recency of use, so a long-running but
  // never-completing trace cannot pin the map by being repeatedly touched.
  private final LinkedHashMap<String, SpanEnrichmentAccumulator> states = new LinkedHashMap<>();

  // One-shot guard so a sustained leak logs once at WARN rather than on every eviction.
  private boolean evictionWarned = false;

  /**
   * Returns the accumulator for {@code traceKey}, creating (and inserting) it if absent. When
   * insertion would exceed {@link #MAX_TRACES}, the eldest entry is evicted first so the store
   * stays bounded.
   */
  synchronized SpanEnrichmentAccumulator getOrCreate(final String traceKey) {
    SpanEnrichmentAccumulator existing = states.get(traceKey);
    if (existing != null) {
      return existing;
    }
    if (states.size() >= MAX_TRACES) {
      evictEldest();
    }
    final SpanEnrichmentAccumulator created = new SpanEnrichmentAccumulator();
    states.put(traceKey, created);
    return created;
  }

  /** Removes and returns the accumulator for {@code traceKey}, or {@code null} if absent. */
  synchronized SpanEnrichmentAccumulator remove(final String traceKey) {
    return states.remove(traceKey);
  }

  /** Clears all tracked state (cleanup on provider close / unbind). */
  synchronized void clear() {
    states.clear();
  }

  synchronized int size() {
    return states.size();
  }

  synchronized boolean isEmpty() {
    return states.isEmpty();
  }

  // ---- test-only accessor ----

  synchronized SpanEnrichmentAccumulator peek(final String traceKey) {
    return states.get(traceKey);
  }

  private void evictEldest() {
    final Iterator<Map.Entry<String, SpanEnrichmentAccumulator>> it = states.entrySet().iterator();
    if (it.hasNext()) {
      it.next();
      it.remove();
    }
    if (!evictionWarned) {
      evictionWarned = true;
      log.warn(
          "Span-enrichment state cap ({}) reached; evicting oldest in-flight trace state. "
              + "This indicates traces with flag evaluations that never complete; enrichment for "
              + "evicted traces is dropped to bound memory.",
          MAX_TRACES);
    }
  }
}
